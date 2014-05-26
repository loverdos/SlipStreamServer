package com.sixsq.slipstream.connector.okeanos;

import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.CliConnectorBase;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.credentials.Credentials;
import com.sixsq.slipstream.exceptions.*;
import com.sixsq.slipstream.persistence.*;
import com.sixsq.slipstream.util.ProcessUtils;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * @author Christos KK Loverdos <loverdos@gmail.com>
 */
public class OkeanosConnector extends CliConnectorBase {
    private static Logger log = Logger.getLogger(OkeanosConnector.class.toString());
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

    public Connector copy() { return new OkeanosConnector(getConnectorInstanceName()); }

    public String getCloudServiceName() { return OkeanosConnector.CLOUD_SERVICE_NAME; }

    protected String getInstanceType(Run run) throws SlipStreamClientException, ConfigurationException {
        return (isInOrchestrationContext(run))
            ? Configuration.
                getInstance().
                getRequiredProperty(
                    constructKey(OkeanosUserParametersFactory.ORCHESTRATOR_INSTANCE_TYPE_PARAMETER_NAME)
                )
            : getInstanceType(ImageModule.load(run.getModuleResourceUrl()));
    }

    private void validateRun(Run run, User user) throws ConfigurationException, SlipStreamClientException, ServerExecutionEnginePluginException {
        final String instanceSize = getInstanceType(run);
        if (instanceSize == null || instanceSize.isEmpty() || "".equals(instanceSize) ){
            throw new ValidationException("Instance type cannot be empty.");
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
            ? getOrchestratorName(run) + "-" + run.getUuid()
            : "machine" + "-" + run.getUuid();
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

        String userData = "#!/bin/sh -e \n";
        userData += "# SlipStream contextualization script for VMs on Amazon. \n";
        userData += "export SLIPSTREAM_CLOUD=\"" + getCloudServiceName() + "\"\n";
        userData += "export SLIPSTREAM_CONNECTOR_INSTANCE=\"" + getConnectorInstanceName() + "\"\n";
        userData += "export SLIPSTREAM_NODENAME=\"" + nodename + "\"\n";
        userData += "export SLIPSTREAM_DIID=\"" + run.getName() + "\"\n";
        userData += "export SLIPSTREAM_REPORT_DIR=\"" + SLIPSTREAM_REPORT_DIR + "\"\n";
        userData += "export SLIPSTREAM_SERVICEURL=\"" + configuration.baseUrl + "\"\n";
        userData += "export SLIPSTREAM_BUNDLE_URL=\"" + configuration.getRequiredProperty("slipstream.update.clienturl") + "\"\n";
        userData += "export SLIPSTREAM_BOOTSTRAP_BIN=\"" + configuration.getRequiredProperty("slipstream.update.clientbootstrapurl") + "\"\n";
        userData += "export CLOUDCONNECTOR_BUNDLE_URL=\"" + configuration.getRequiredProperty("cloud.connector.library.libcloud.url") + "\"\n";
        userData += "export CLOUDCONNECTOR_PYTHON_MODULENAME=\"" + CLOUDCONNECTOR_PYTHON_MODULENAME + "\"\n";
        userData += "export OPENSTACK_SERVICE_TYPE=\"" + configuration.getRequiredProperty(constructKey(OkeanosUserParametersFactory.SERVICE_TYPE_PARAMETER_NAME)) + "\"\n";
        userData += "export OPENSTACK_SERVICE_NAME=\"" + configuration.getRequiredProperty(constructKey(OkeanosUserParametersFactory.SERVICE_NAME_PARAMETER_NAME)) + "\"\n";
        userData += "export OPENSTACK_SERVICE_REGION=\"" + configuration.getRequiredProperty(constructKey(OkeanosUserParametersFactory.SERVICE_REGION_PARAMETER_NAME)) + "\"\n";
        userData += "export SLIPSTREAM_CATEGORY=\"" + run.getCategory().toString() + "\"\n";
        userData += "export SLIPSTREAM_USERNAME=\"" + username + "\"\n";
        userData += "export SLIPSTREAM_COOKIE=" + getCookieForEnvironmentVariable(username) + "\n";
        userData += "export SLIPSTREAM_VERBOSITY_LEVEL=\"" + getVerboseParameterValue(user) + "\"\n";

		/*userData += "mkdir -p ~/.ssh\n"
				+ "echo '" + getPublicSshKey(run, user) + "' >> ~/.ssh/authorized_keys\n"
				+ "chmod 0700 ~/.ssh\n"
				+ "chmod 0640 ~/.ssh/authorized_keys\n";
		*/
        userData += "mkdir -p " + SLIPSTREAM_REPORT_DIR + "\n"
            + "wget --secure-protocol=SSLv3 --no-check-certificate -O " + bootstrap
            + " $SLIPSTREAM_BOOTSTRAP_BIN > " + SLIPSTREAM_REPORT_DIR + "/"
            + logfilename + " 2>&1 " + "&& chmod 0755 " + bootstrap + "\n"
            + bootstrap + " " + targetScript + " >> "
            + SLIPSTREAM_REPORT_DIR + "/" + logfilename + " 2>&1\n";

        log.info(userData);

        return userData;
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

    public Run launch(Run run, User user) throws SlipStreamException {
        try {
            validateRun(run, user);
            final List<String> cmdline = getRunInstanceCmdline(run, user);
            final String[] cmdlineArray = toArray(cmdline);
            final String result = ProcessUtils.execGetOutput(cmdlineArray, false);

            final String[] instanceData = parseRunInstanceResult(result);
            final String instanceId = instanceData[0];
            final String ipAddress = instanceData[1];

            updateInstanceIdAndIpOnRun(run, instanceId, ipAddress);
        } catch (IOException e) {
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
        } finally {
            deleteTempSshKeyFile();
        }

        return run;
    }

    public Credentials getCredentials(User user) {
        return new OkeanosCredentials(user, getConnectorInstanceName());
    }

    public Properties describeInstances(User user) throws SlipStreamException {
        validateCredentials(user);

        final List<String> cmdline = mkList(COMMAND_DESCRIBE_INSTANCES, getCommandUserParams(user));
        final String[] cmdlineArray = toArray(cmdline);

        try {
            final String result = ProcessUtils.execGetOutput(cmdlineArray);
            return parseDescribeInstanceResult(result);
        } catch (IOException e) {
            log.log(Level.SEVERE, "describeInstances", e);
            throw new SlipStreamInternalException(e);
        }
    }

    public void terminate(Run run, User user) throws SlipStreamException {
        validateCredentials(user);

        log.info(getConnectorInstanceName() + ". Terminating all instances.");

        final List<String> cmdlineTpl = mkList(
            COMMAND_TERMINATE_INSTANCES,
            getCommandUserParams(user),
            "--instance-id"
        );

        for (final String id : getCloudNodeInstanceIds(run)) {
            final List<String> cmdline = mkList(cmdlineTpl, id);
            final String[] cmdlineArray = toArray(cmdline);

            try {
                ProcessUtils.execGetOutput(cmdlineArray);
            } catch (SlipStreamClientException e) {
                log.log(Level.WARNING, "terminate(). Failed to terminate instance " + id, e);
            } catch (IOException e) {
                log.log(Level.WARNING, "terminate()", e);
            }
        }
    }

    @Override
    public Map<String, UserParameter> getUserParametersTemplate() throws ValidationException {
        return new OkeanosUserParametersFactory(getConnectorInstanceName()).getParameters();
    }
}
