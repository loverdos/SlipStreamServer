package com.sixsq.slipstream.connector.okeanos;

import com.sixsq.slipstream.connector.CredentialsBase;
import com.sixsq.slipstream.credentials.Credentials;
import com.sixsq.slipstream.exceptions.InvalidElementException;
import com.sixsq.slipstream.exceptions.SlipStreamRuntimeException;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.persistence.User;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Christos KK Loverdos <loverdos@gmail.com>
 */
public class OkeanosCredentials extends CredentialsBase implements Credentials {
    private static Logger log = Logger.getLogger(OkeanosCredentials.class.toString());
    public OkeanosCredentials(User user, String connectorInstanceName) {
        super(user);
        try {
            cloudParametersFactory = new OkeanosUserParametersFactory(connectorInstanceName);
        } catch (ValidationException e) {
            log.log(Level.SEVERE, "Creating cloudParametersFactory", e);
            throw new SlipStreamRuntimeException(e);
        }
    }

    public String getKey() throws InvalidElementException {
        return getParameterValue(OkeanosUserParametersFactory.KEY_PARAMETER_NAME);
    }

    public String getSecret() throws InvalidElementException {
        return getParameterValue(OkeanosUserParametersFactory.SECRET_PARAMETER_NAME);
    }

}
