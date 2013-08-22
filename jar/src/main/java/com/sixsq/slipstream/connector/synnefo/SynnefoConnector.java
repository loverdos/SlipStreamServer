package com.sixsq.slipstream.connector.synnefo;

import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.openstack.OpenStackConnector;

import java.util.logging.Logger;

/**
 * Connector for synnefo.org
 */
public class SynnefoConnector extends OpenStackConnector {
    private static Logger log = Logger.getLogger(SynnefoConnector.class.toString());

    public static final String CLOUD_SERVICE_NAME = "okeanos";
    public static final String CLOUDCONNECTOR_PYTHON_MODULENAME = "slipstream.cloudconnectors.openstack.OpenStackClientCloud";

    public SynnefoConnector() {
        this(CLOUD_SERVICE_NAME);
    }

    public SynnefoConnector(String instanceName){
        super(instanceName);
    }

    public Connector copy(){
        return new OpenStackConnector(getConnectorInstanceName());
    }

    public String getCloudServiceName() {
        return SynnefoConnector.CLOUD_SERVICE_NAME;
    }

    public String getJcloudsDriverName() {
        return OpenStackConnector.JCLOUDS_DRIVER_NAME;
    }
}
