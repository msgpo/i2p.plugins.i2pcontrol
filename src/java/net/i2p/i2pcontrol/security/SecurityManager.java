package net.i2p.i2pcontrol.security;
/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.sun.org.apache.xml.internal.security.utils.Base64;

import sun.misc.BASE64Encoder;


import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA256Generator;
import net.i2p.i2pcontrol.security.jbcrypt.BCrypt;
import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;
import net.i2p.util.Log;

/**
 * Manage the password storing for I2PControl.
 */
public class SecurityManager {
	public final static String CERT_ALIAS = "CA";
	private final static String SSL_PROVIDER = "SunJSSE";
	private final static String DEFAULT_AUTH_BCRYPT_SALT = "$2a$11$5aOLx2x/8i4fNaitoCSSWu";
	private final static String DEFAULT_AUTH_PASSWORD = "$2a$11$5aOLx2x/8i4fNaitoCSSWuut2wEl3Hupuca8DCT.NXzvH9fq1pBU.";
	private final static HashMap<String,AuthToken> authTokens;
	private final static Timer timer;
	private static String[] SSL_CIPHER_SUITES;
	private static KeyStore _ks;
	private static Log _log;
	
	static {
		_log = I2PAppContext.getGlobalContext().logManager().getLog(SecurityManager.class);
		authTokens = new HashMap<String,AuthToken>();
		
		timer = new Timer();
		// Start running periodic task after 20 minutes, run periodically every 10th minute.
		timer.scheduleAtFixedRate(new Sweeper(), 1000*60*20, 1000*60*10);

		// Get supported SSL copher suites.
		SocketFactory SSLF = SSLSocketFactory.getDefault();
		try{
			SSL_CIPHER_SUITES =  ((SSLSocket)SSLF.createSocket()).getSupportedCipherSuites();
		} catch (Exception e){
			_log.log(Log.CRIT, "Unable to create SSLSocket used for fetching supported ssl cipher suites.", e);
		}
		
		// Initialize keystore (if needed)
		_ks = KeyStoreInitializer.getKeyStore();
	}
	
	public static String[] getSupprtedSSLCipherSuites(){
		return SSL_CIPHER_SUITES;
	}
	
	public static String getSecurityProvider(){
		return SSL_PROVIDER;
	}
	
	public static String getKeyStoreLocation(){
		return KeyStoreFactory.DEFAULT_KEYSTORE_LOCATION;
	}
	
	public static String getKeyStorePassword(){
		return KeyStoreFactory.DEFAULT_KEYSTORE_PASSWORD;
	}
	
	public static String getKeyStoreType(){
		return KeyStoreFactory.DEFAULT_KEYSTORE_TYPE;
	}
	
	/**
	 * Return the X509Certificate of the server as a Base64 encoded string.
	 * @return base64 encode of X509Certificate
	 */
	public static String getBase64Cert(){
		X509Certificate caCert = KeyStoreFactory.readCert(_ks,
				CERT_ALIAS, 
				KeyStoreFactory.DEFAULT_KEYSTORE_PASSWORD);
		return getBase64FromCert(caCert);
	}
	
	/**
	 * Return the X509Certificate as a base64 encoded string.
	 * @param cert
	 * @return base64 encode of X509Certificate
	 */
	private static String getBase64FromCert(X509Certificate cert){
		BASE64Encoder encoder = new BASE64Encoder();
		try {
			return encoder.encodeBuffer(cert.getEncoded());
		} catch (CertificateEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	
	
	/**
	 * Hash pwd with using BCrypt with the default salt.
	 * @param pwd
	 * @return BCrypt hash of salt and input string
	 */
	public static String getPasswdHash(String pwd){
		return BCrypt.hashpw(pwd, ConfigurationManager.getInstance().getConf("auth.salt", DEFAULT_AUTH_BCRYPT_SALT));
	}

	/**
	 * Hash input one time with SHA-256, return Base64 encdoded string.
	 * @param string
	 * @return
	 */
	public static String getHash(String string) {
		SHA256Generator hashGen = new SHA256Generator(I2PAppContext.getGlobalContext());
		byte[] bytes = string.getBytes();
		bytes = hashGen.calculateHash(bytes).toByteArray();
		return new String(Base64.encode(bytes));
	}
	
	
	/**
	 * Add a Authentication Token if the provided password is valid.
	 * The token will be valid for one day.
	 * @return Returns AuthToken if password is valid. If password is invalid null will be returned. 
	 */
	public static AuthToken validatePasswd(String pwd){
		String storedPass = ConfigurationManager.getInstance().getConf("auth.password", DEFAULT_AUTH_PASSWORD);
		if (getPasswdHash(pwd).equals(storedPass)){
			AuthToken token = new AuthToken(pwd);
			authTokens.put(token.getId(), token);
			return token;
		} else {
			return null;
		}
	}
	

	/**
	 * Checks whether the AuthToken with the given ID exists and if it does whether is has expired.
	 * @param tokenID - The token to validate
	 * @throws InvalidAuthTokenException
	 * @throws ExpiredAuthTokenException
	 */
	public static void verifyToken(String tokenID) throws InvalidAuthTokenException, ExpiredAuthTokenException {
		AuthToken token = authTokens.get(tokenID);
		if (token == null){
			throw new InvalidAuthTokenException("AuthToken with ID: " + tokenID + " couldn't be found.");
		} else if (!token.isValid()){
			System.out.println("token.isValid: " + token.isValid()); // Delete me
			authTokens.remove(token.getId());
			throw new ExpiredAuthTokenException("AuthToken with ID: " + tokenID + " expired " + token.getExpiryTime(), token.getExpiryTime());
		} else {
			return; // Everything is fine. :)
		}	
	}
	
	/**
	 * Clean up old authorization tokens to keep the token store slim and fit.
	 * @author hottuna
	 *
	 */
	private static class Sweeper extends TimerTask{	
		@Override
		public void run(){
			_log.debug("Starting cleanup job..");
			ArrayList<String> arr = new ArrayList<String>();
			for (Map.Entry<String,AuthToken> e : authTokens.entrySet()){
				AuthToken token = e.getValue();
				if (!token.isValid()){
					arr.add(e.getKey());
				}
			}
			for (String s : arr){
				authTokens.remove(s);
			}
			_log.debug("Cleanup job done.");
		}
	}
	
	
}
