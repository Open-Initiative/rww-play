/*
 * Copyright 2012 Henry Story, http://bblfish.net/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package test

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.{KeyPairGenerator, SecureRandom, KeyPair}
import java.util.Date
import javax.net.ssl.X509TrustManager
import sun.security.x509._


class WebIDTrustManager extends X509TrustManager {

  def checkClientTrusted(p1: Array[X509Certificate], p2: String) {}

  def checkServerTrusted(p1: Array[X509Certificate], p2: String) {}

  def getAcceptedIssuers = Array(WebID.cert)
}

/**
 * An arbitrary WebID certificate
 * The only important part is the DnName, the rest is just to fill in the API
 **/
object WebID {
  val DnName = "CN=WebID, O=W3C"

  val cert = {
    // Generate the key pair
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(512)
    val keyPair = keyPairGenerator.generateKeyPair()
    createSelfSignedCertificate(keyPair)
  }

  def createSelfSignedCertificate(keyPair: KeyPair): X509Certificate = {
    val certInfo = new X509CertInfo()

    // Serial number and version
    certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())))
    certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))

    // Validity
    val validFrom = new Date()
    val validTo = new Date(validFrom.getTime + 50l * 365l * 24l * 60l * 60l * 1000l)
    val validity = new CertificateValidity(validFrom, validTo)
    certInfo.set(X509CertInfo.VALIDITY, validity)

    // Subject and issuer
    val owner = new X500Name(DnName)
    certInfo.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner))
    certInfo.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner))

    // Key and algorithm
    certInfo.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic))
    val algorithm = new AlgorithmId(AlgorithmId.sha1WithRSAEncryption_oid)
    certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm))

    // Create a new certificate and sign it
    val cert = new X509CertImpl(certInfo)
    cert.sign(keyPair.getPrivate, "SHA1withRSA")

    // Since the SHA1withRSA provider may have a different algorithm ID to what we think it should be,
    // we need to reset the algorithm ID, and resign the certificate
    val actualAlgorithm = cert.get(X509CertImpl.SIG_ALG).asInstanceOf[AlgorithmId]
    certInfo.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, actualAlgorithm)
    val newCert = new X509CertImpl(certInfo)
    newCert.sign(keyPair.getPrivate, "SHA1withRSA")
    newCert
  }
}