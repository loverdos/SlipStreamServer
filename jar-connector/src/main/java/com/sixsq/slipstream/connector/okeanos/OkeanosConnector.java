package com.sixsq.slipstream.connector.okeanos;

import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.CliConnectorBase;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.UserParametersFactoryBase;
import com.sixsq.slipstream.credentials.Credentials;
import com.sixsq.slipstream.exceptions.*;
import com.sixsq.slipstream.persistence.*;

import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * @author Christos KK Loverdos <loverdos@gmail.com>
 */
public class OkeanosConnector extends CliConnectorBase {
    public static final String ClassName = OkeanosConnector.class.getName();
    private static Logger log = Logger.getLogger(ClassName);
    public static final String CLOUD_SERVICE_NAME = "okeanos";
    public static final String CLOUDCONNECTOR_PYTHON_MODULENAME = "slipstream.cloudconnectors.okeanos.OkeanosClientCloud";

    public static final String COMMAND_DESCRIBE_INSTANCES  = format("%s/okeanos-describe-instances", CLI_LOCATION);
    public static final String COMMAND_RUN_INSTANCES       = format("%s/okeanos-run-instances", CLI_LOCATION);
    public static final String COMMAND_TERMINATE_INSTANCES = format("%s/okeanos-terminate-instances", CLI_LOCATION);

    public OkeanosConnector() { this(OkeanosConnector.CLOUD_SERVICE_NAME); }

    public OkeanosConnector(String instanceName) { super(instanceName); }

    public boolean isEmptyOrNull(String s) { return s == null || s.isEmpty(); }

    public List<String> mkList(String... args) { return Arrays.asList(args); }

    public List<String> mkList(String command, List<String> others, String... more) {
        final List<String> list = new ArrayList<String>(1 + others.size() + more.length);
        list.add(command);
        list.addAll(others);
        list.addAll(mkList(more));
        return list;
    }

    public List<String> mkList(List<String> list, String ...more) {
        final List<String> bigOne = new ArrayList<String>(list);
        bigOne.addAll(mkList(more));
        return bigOne;
    }

    public String[] toArray(List<String> list) { return list.toArray(new String[list.size()]); }

    protected String constructKey(String key) throws ValidationException {
        return new OkeanosUserParametersFactory(getConnectorInstanceName()).constructKey(key);
    }

    @Override
    public Map<String, ServiceConfigurationParameter> getServiceConfigurationParametersTemplate()
        throws ValidationException {
        return new OkeanosSystemConfigurationParametersFactory(getConnectorInstanceName()).getParameters();
    }

    @Override
    public Map<String, ModuleParameter> getImageParametersTemplate() throws ValidationException {
        return new OkeanosImageParametersFactory(getConnectorInstanceName()).getParameters();
    }

    public Connector copy() { return new OkeanosConnector(getConnectorInstanceName()); }

    public String getCloudServiceName() { return OkeanosConnector.CLOUD_SERVICE_NAME; }

    // instance type [slipstream] == flavor [~okeanos]
    protected String getInstanceType(Run run) throws SlipStreamClientException, ConfigurationException {
        final boolean inOrchestrationContext = isInOrchestrationContext(run);
        log.info("KK [okeanos::getInstanceType], inOrchestrationContext = " + inOrchestrationContext);
        log.info("KK [okeanos::getInstanceType], constructKey(" +OkeanosUserParametersFactory.ORCHESTRATOR_INSTANCE_TYPE_PARAMETER_NAME+ ") = " + constructKey(OkeanosUserParametersFactory.ORCHESTRATOR_INSTANCE_TYPE_PARAMETER_NAME));
        return inOrchestrationContext
            ? Configuration.
                getInstance().
                getRequiredProperty(
                    constructKey(OkeanosUserParametersFactory.ORCHESTRATOR_INSTANCE_TYPE_PARAMETER_NAME)
                )
            : getInstanceType(ImageModule.load(run.getModuleResourceUrl()));
    }

    private void validateRun(Run run, User user) throws ConfigurationException, SlipStreamClientException, ServerExecutionEnginePluginException {
        final String instanceType = getInstanceType(run);
        if(isEmptyOrNull(instanceType)) {
            throw new ValidationException("Instance type (flavor) cannot be empty");
        }
        final String imageId = getImageId(run, user);
        if (isEmptyOrNull(imageId)){
            throw new ValidationException("Image ID cannot be empty");
        }
    }

    protected void validateCredentials(User user) throws ValidationException {
        super.validateCredentials(user);

        final String endpoint = getEndpoint(user);
        if (isEmptyOrNull(endpoint)) {
            throw new ValidationException("Cloud Endpoint cannot be empty. Please contact your SlipStream administrator.");
        }
    }

    protected String getRegion() throws ConfigurationException, ValidationException {
        return Configuration.
            getInstance().
            getRequiredProperty(
                constructKey(OkeanosUserParametersFactory.SERVICE_REGION_PARAMETER_NAME)
            );
    }

    protected String getServiceType() throws ConfigurationException, ValidationException {
        return Configuration.
            getInstance().
            getRequiredProperty(
                constructKey(OkeanosUserParametersFactory.SERVICE_TYPE_PARAMETER_NAME)
            );
    }

    protected String getServiceName() throws ConfigurationException, ValidationException {
        return Configuration.
            getInstance().
            getRequiredProperty(
                constructKey(OkeanosUserParametersFactory.SERVICE_NAME_PARAMETER_NAME)
            );
    }

    @Override
    protected String getKey(User user) {
        try {
            return getCredentials(user).getKey();
        }
        catch(InvalidElementException e) {
            throw new SlipStreamRuntimeException(e);
        }
    }

    @Override
    protected String getSecret(User user) {
        try {
            return getCredentials(user).getSecret();
        }
        catch(InvalidElementException e) {
            throw new SlipStreamRuntimeException(e);
        }
    }

    @Override
    protected String getEndpoint(User user) {
        return user.
            getParameter(
                getConnectorInstanceName() + "." + UserParametersFactoryBase.ENDPOINT_PARAMETER_NAME
            ).getValue();
    }

    private List<String> getCommandUserParams(User user) throws ValidationException {
        return mkList(
            "--username", getKey(user),
            "--password", getSecret(user),
            "--endpoint", getEndpoint(user),
            "--region", getRegion(),
            "--service-type", getServiceType(),
            "--service-name", getServiceName()
        );
    }

    protected String getVmName(Run run) {
        return run.getType() == RunType.Orchestration
            ? getOrchestratorName(run) + "-" + run.getUser() + "-" + run.getUuid()
            : "machine" + "-" + run.getUser() + "-" + run.getUuid();
    }

    protected String getNetwork(Run run) throws ValidationException{
        if (run.getType() == RunType.Orchestration) {
            return "";
        } else {
            ImageModule machine = ImageModule.load(run.getModuleResourceUrl());
            return machine.getParameterValue(ImageModule.NETWORK_KEY, null);
        }
    }

    protected String getSecurityGroups(Run run) throws ValidationException {
        return isInOrchestrationContext(run)
            ? "default"
            : getParameterValue(
                OkeanosImageParametersFactory.SECURITY_GROUPS,
                ImageModule.load(run.getModuleResourceUrl()));
    }

    static class Script {
        final StringBuilder sb = new StringBuilder();
        Script() {}

        Script comment(String comment) {
            sb.append(format("# %s\n", comment));
            return this;
        }

        Script export(String name, String value) {
            sb.append(format("export %s=\"%s\"\n", name, value));
            return this;
        }

        Script nl() {
            sb.append("\n");
            return this;
        }

        Script command(String ...args) {
            for(int i = 0; i < args.length; i++) {
                final String arg = args[i];
                sb.append(arg);
                if(i < args.length) { sb.append(' '); }
            }
            if(args.length > 0) { sb.append('\n'); }

            return this;
        }

        Script commandWithEcho(String ...args) {
            final StringBuilder sbEcho = new StringBuilder();
            final StringBuilder sbCmd = new StringBuilder();

            if(args.length > 0) {
                sbEcho.append("echo ");
            }

            for(int i = 0; i < args.length; i++) {
                final String arg = args[i];

                sbEcho.append(arg);
                sbCmd.append(arg);

                if(i < args.length) {
                    sbEcho.append(' ');
                    sbCmd.append(' ');
                }
            }
            if(args.length > 0) {
                sbEcho.append('\n');
                sbCmd.append('\n');

                sb.append(sbEcho);
                sb.append(sbCmd);
            }

            return this;
        }


        Script raw(String s) {
            sb.append(s);
            return this;
        }

        @Override
        public String toString() { return sb.toString(); }
    }

    @Override
    protected String getCookieForEnvironmentVariable(String identifier) {
        return generateCookie(identifier);
    }

    protected String createContextualizationData(Run run, User user) throws ConfigurationException, ServerExecutionEnginePluginException, SlipStreamClientException {

        final Configuration configuration = Configuration.getInstance();

        final String logfilename = "orchestrator.slipstream.log";
        final String bootstrap = "/tmp/slipstream.bootstrap";
        final String username = user.getName();

        final String targetScript;
        final String nodename;
        if(isInOrchestrationContext(run)){
            targetScript = "slipstream-orchestrator";
            nodename = getOrchestratorName(run);
        } else {
            targetScript = "";
            nodename = Run.MACHINE_NAME;
        }

        final Script script = new Script().
            raw("#!/bin/sh -e\n").
            comment("Slipstream contextualization script for ~Okeanos").
            export("SLIPSTREAM_CLOUD", getCloudServiceName()).
            export("SLIPSTREAM_CONNECTOR_INSTANCE", getConnectorInstanceName()).
            export("SLIPSTREAM_NODENAME", nodename).
            export("SLIPSTREAM_DIID", run.getName()).
            export("SLIPSTREAM_REPORT_DIR", SLIPSTREAM_REPORT_DIR).
            export("SLIPSTREAM_SERVICEURL", configuration.baseUrl).
            export("SLIPSTREAM_BUNDLE_URL", configuration.getRequiredProperty("slipstream.update.clienturl")).
            export("SLIPSTREAM_BOOTSTRAP_BIN", configuration.getRequiredProperty("slipstream.update.clientbootstrapurl")).
            export("SLIPSTREAM_CATEGORY", run.getCategory().toString()).
            export("SLIPSTREAM_USERNAME", username).
            export("SLIPSTREAM_COOKIE", getCookieForEnvironmentVariable(username)).
            export("SLIPSTREAM_VERBOSITY_LEVEL", getVerboseParameterValue(user)).

            nl().
            export("CLOUDCONNECTOR_BUNDLE_URL", configuration.getRequiredProperty("cloud.connector.library.libcloud.url")).
            export("CLOUDCONNECTOR_PYTHON_MODULENAME", CLOUDCONNECTOR_PYTHON_MODULENAME).
            export("OKEANOS_SERVICE_TYPE", configuration.getRequiredProperty(constructKey(OkeanosUserParametersFactory.SERVICE_TYPE_PARAMETER_NAME))).
            export("OKEANOS_SERVICE_NAME", configuration.getRequiredProperty(constructKey(OkeanosUserParametersFactory.SERVICE_NAME_PARAMETER_NAME))).
            export("OKEANOS_SERVICE_REGION", configuration.getRequiredProperty(constructKey(OkeanosUserParametersFactory.SERVICE_REGION_PARAMETER_NAME))).

            nl().
            comment("This is for testing purposes from the command-line, technically not needed in production").
            export("PYTHONPATH", "/opt/slipstream/client/lib").
            comment("Also for testing purposes. These are defined in /tmp/slipstream.bootstrap as it is deployed from this script").
            export("SLIPSTREAM_CLIENT_HOME", "/opt/slipstream/client").
            export("SLIPSTREAM_HOME", "/opt/slipstream/client/sbin").

            nl().
            command("mkdir", "-p", SLIPSTREAM_REPORT_DIR).

            nl().
            command(
                "wget", "--secure-protocol=SSLv3", "--no-check-certificate", "-O",
                bootstrap,
                "$SLIPSTREAM_BOOTSTRAP_BIN",
                ">", SLIPSTREAM_REPORT_DIR + "/" + logfilename, "2>&1",
                "&&",
                "chmod", "0755", bootstrap).

            nl().
            command(bootstrap, targetScript, ">>", SLIPSTREAM_REPORT_DIR + "/" + logfilename, "2>&1").

            nl().
            command("exec exit", "$?");

        final String scriptString = script.toString();
        log.info(scriptString);
        return scriptString;
    }

    private List<String> getRunInstanceCmdline(Run run, User user) throws SlipStreamClientException, IOException, ConfigurationException, ServerExecutionEnginePluginException {
        return mkList(
            COMMAND_RUN_INSTANCES,
            getCommandUserParams(user),
            "--instance-type", getInstanceType(run),
            "--image-id", getImageId(run, user),
            "--instance-name", getVmName(run),
            "--network-type", getNetwork(run),
            "--security-groups", getSecurityGroups(run),
            "--public-key", getPublicSshKey(run, user),
            "--context-script", createContextualizationData(run, user)
        );
    }

    private String trimTo(StringBuilder s, int length) {
        if(s.length() <= length) {
            return s.toString();
        } else {
            return s.substring(0, length);
        }
    }

    private String exec(List<String> cmdline) throws IOException, InterruptedException {
        final class OutputThread extends Thread {
            final String logPrefix;
            final InputStream in;
            final StringBuilder sb;

            OutputThread(String logPrefix, InputStream in, StringBuilder sb) {
                this.logPrefix = logPrefix;
                this.in = in;
                this.sb = sb;
            }

            @Override
            public void run() {
                final InputStreamReader isr = new InputStreamReader(in);
                final BufferedReader br = new BufferedReader(isr);

                String line = null;
                try {
                    while((line = br.readLine()) != null) {
                        log.info(logPrefix + line);
                        sb.append(line);
                        sb.append(System.getProperty("line.separator"));
                    }
                    if(sb.length() > 0) {
                        sb.setLength(sb.length() - 1);
                    }

                    in.close();
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
            }
        }

        final StringBuilder cmdsb = new StringBuilder();
        for(String s : cmdline) {
            cmdsb.append(s);
            cmdsb.append(' '); // never mind one more
        }
        final String trimmedCmd = trimTo(cmdsb, 80);

        log.info(cmdsb.toString());

        final StringBuilder stdoutBuffer  = new StringBuilder(1024);
        final StringBuilder stderrBuffer = new StringBuilder(1024);
        final ProcessBuilder pb = new ProcessBuilder(cmdline);
        final Process proc = pb.start();
        final OutputThread stdoutThread = new OutputThread("STDOUT ", proc.getInputStream(), stdoutBuffer);
        final OutputThread stderrThread = new OutputThread("STDERR ", proc.getErrorStream(), stderrBuffer);

        stdoutThread.start();
        stderrThread.start();
        final int procResult = proc.waitFor();
        stdoutThread.join();
        stderrThread.join();

        if(stdoutBuffer.length() > 0) { log.info("STDOUT of " + trimmedCmd + "\n" + stdoutBuffer); }
        if(stderrBuffer.length() > 0) { log.info("STDERR of " + trimmedCmd + "\n" + stderrBuffer); }

        if(procResult != 0) {
            final String msg = "Error " + procResult + " executing " + cmdline.get(0);
            System.err.println(msg);
//            System.err.println("STDIN:\n" + stdoutBuffer);
//            System.err.println("STDERR:\n" + stderrBuffer);
            throw new ProcessException(msg, stdoutBuffer.toString());
        }

        return stdoutBuffer.toString();
    }

    public static final class RunInstanceReturnData {
        public final String instanceId;
        public final String ipv4;
        public final int exitCode;
        public final String adminPass;

        public RunInstanceReturnData(String instanceId, String ipv4, int exitCode, String adminPass) {
            this.instanceId = instanceId;
            this.ipv4 = ipv4;
            this.exitCode = exitCode;
            this.adminPass = adminPass;
        }

        @Override
        public boolean equals(Object o) {
            if(this == o) {
                return true;
            }
            if(o == null || getClass() != o.getClass()) {
                return false;
            }

            RunInstanceReturnData that = (RunInstanceReturnData) o;

            return exitCode == that.exitCode && adminPass.equals(that.adminPass) && instanceId.equals(that.instanceId) && ipv4.equals(that.ipv4);

        }

        @Override
        public int hashCode() {
            int result = instanceId.hashCode();
            result = 31 * result + ipv4.hashCode();
            result = 31 * result + exitCode;
            result = 31 * result + adminPass.hashCode();
            return result;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("RunInstanceReturnData(");
            sb.append("instanceId='").append(instanceId).append('\'');
            sb.append(", ipv4='").append(ipv4).append('\'');
            sb.append(", exitCode=").append(exitCode);
            sb.append(", adminPass='").append(adminPass).append('\'');
            sb.append(')');
            return sb.toString();
        }
    }

    protected void checkScannerNext(Scanner scanner, String stdout) throws SlipStreamClientException {
        if(!scanner.hasNext()) {
            throw new SlipStreamClientException("Error returned by launch command. Got: " + stdout);
        }
    }
    protected RunInstanceReturnData _parseRunInstanceResult(String stdout) throws SlipStreamClientException {
        final Scanner scanner = new Scanner(stdout);

        checkScannerNext(scanner, stdout);
        final String instanceId = scanner.next();

        checkScannerNext(scanner, stdout);
        final String ipv4 = scanner.next();

        checkScannerNext(scanner, stdout);
        final int exitCode = scanner.nextInt();

        checkScannerNext(scanner, stdout);
        final String adminPass = scanner.next();

        return new RunInstanceReturnData(instanceId, ipv4, exitCode, adminPass);
    }

    public Run launch(Run run, User user) throws SlipStreamException {
        final String methodInfo = format("launch(%s)", user);
        log.entering(ClassName, methodInfo);
        try {
            validateRun(run, user);
            final List<String> cmdline = getRunInstanceCmdline(run, user);
            final String result = exec(cmdline);

            final RunInstanceReturnData instanceData = _parseRunInstanceResult(result);
            log.info(instanceData.toString());

            updateInstanceIdAndIpOnRun(run, instanceData.instanceId, instanceData.ipv4);
        } catch (IOException|InterruptedException e) {
            log.log(Level.SEVERE, "launch", e);
            throw new SlipStreamException("Failed getting run instance command", e);
        } catch (ProcessException e) {
            log.log(Level.SEVERE, "launch", e);
            try {
                final String[] instanceData = parseRunInstanceResult(e.getStdOut());
                updateInstanceIdAndIpOnRun(run, instanceData[0], instanceData[1]);
            } catch (Exception ex) {
                log.log(Level.WARNING, "launch: updateInstanceIdAndIpOnRun()", ex);
            }
            throw e;
        }
        finally {
            log.exiting(ClassName, methodInfo);
            deleteTempSshKeyFile();
        }

        return run;
    }

    public Credentials getCredentials(User user) {
        return new OkeanosCredentials(user, getConnectorInstanceName());
    }

    protected Properties _parseDescribeInstanceResult(String stdout) throws SlipStreamException {
        final Properties props = new Properties();
        final StringReader sr = new StringReader(stdout);
        final BufferedReader br = new BufferedReader(sr);
        String line;
        try {
            while((line = br.readLine()) != null) {
                final Scanner scanner = new Scanner(line);
                if(!scanner.hasNext()) {
                    throw new SlipStreamException("Error returned by launch command. Got: " + line);
                }
                final String instanceId = scanner.next();
                if(!scanner.hasNext()) {
                    throw new SlipStreamException("Error returned by launch command. Got: " + line);
                }
                final String status = scanner.next();

                props.put(instanceId, status);
            }
        }
        catch(IOException e) {
            throw new SlipStreamException("While parsing describe instance results", e);
        }

        return props;
    }

    public Properties describeInstances(User user) throws SlipStreamException {
        final String methodInfo = format("describeInstances(%s)", user);
        log.entering(ClassName, methodInfo);
        validateCredentials(user);

        final List<String> cmdline = mkList(COMMAND_DESCRIBE_INSTANCES, getCommandUserParams(user));
        try {
            final String result = exec(cmdline);
            return _parseDescribeInstanceResult(result);
        } catch (IOException|InterruptedException e) {
            log.log(Level.SEVERE, "describeInstances", e);
            throw new SlipStreamInternalException(e);
        } catch (ProcessException e) {
            log.log(Level.SEVERE, "describeInstances", e);
            throw e;
        }
        finally {
            log.exiting(ClassName, methodInfo);
        }
    }

    public void terminate(Run run, User user) throws SlipStreamException {
        final String methodInfo = format("terminate(%s, %s)", run, user);
        log.entering(ClassName, methodInfo);
        validateCredentials(user);

        final List<String> cmdlineTpl = mkList(
            COMMAND_TERMINATE_INSTANCES,
            getCommandUserParams(user),
            "--instance-id"
        );

        for (final String id : getCloudNodeInstanceIds(run)) {
            final List<String> cmdline = mkList(cmdlineTpl, id);

            try {
                log.info("Terminating " + id);
                exec(cmdline);
                log.info("Terminated " + id);
            } catch (IOException|InterruptedException e) {
                log.log(Level.WARNING, "terminate()", e);
            } catch (ProcessException e) {
                log.log(Level.SEVERE, "describeInstances", e);
                throw e;
            }
        }

        log.exiting(ClassName, methodInfo);
    }

    @Override
    public Map<String, UserParameter> getUserParametersTemplate() throws ValidationException {
        return new OkeanosUserParametersFactory(getConnectorInstanceName()).getParameters();
    }
}
