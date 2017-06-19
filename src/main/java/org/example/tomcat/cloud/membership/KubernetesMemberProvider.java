/**
 *  Copyright 2017 Ismaïl Senhaji, Guillaume Pythoud
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
 */

package org.example.tomcat.cloud.membership;

import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.membership.MemberImpl;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.example.tomcat.cloud.stream.StreamProvider;
import org.example.tomcat.cloud.stream.TokenStreamProvider;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.security.AccessController;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class KubernetesMemberProvider implements MemberProvider {
    private static final Log log = LogFactory.getLog(DynamicMembershipService.class);

    // TODO: what about "pure" Kubernetes?
    private static final String ENV_PREFIX = "OPENSHIFT_KUBE_PING_";

    // Kubernetes API URL, constructed from environment variables in init()
    private String url;
    private StreamProvider streamProvider;
    private int connectionTimeout;
    private int readTimeout;

    // Start time of this instance, used to compute aliveTime of peers
    private Instant startTime;
    private MessageDigest md5;

    private Map<String, String> headers = new HashMap<>();

    private int port;
    private String hostName;

    public KubernetesMemberProvider() {
        try {
            md5 = MessageDigest.getInstance("md5");
        } catch (NoSuchAlgorithmException e) {
            // Shouldn't happen
            e.printStackTrace();
        }
    }

    // Get value of environment variable named keys[0]
    // If keys[0] isn't found, try keys[1], keys[2], ...
    // If nothing is found, return null
    private static String getEnv(String... keys) {
        String val = null;

        for (String key : keys) {
            val = AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getenv(key));
            if (val != null)
                break;
        }

        return val;
    }

    @Override
    public void init(Properties properties) throws IOException {
        startTime = Instant.now();

        connectionTimeout = Integer.parseInt(properties.getProperty("connectionTimeout", "1000"));
        readTimeout = Integer.parseInt(properties.getProperty("readTimeout", "1000"));

        hostName = InetAddress.getLocalHost().getHostName();
        port = Integer.parseInt(properties.getProperty("tcpListenPort"));

        // Set up Kubernetes API parameters
        String namespace = getEnv(ENV_PREFIX + "NAMESPACE");
        if (namespace == null || namespace.length() == 0)
            throw new RuntimeException("Namespace not set; clustering disabled");

        log.info(String.format("Namespace [%s] set; clustering enabled", namespace));

        String protocol = getEnv(ENV_PREFIX + "MASTER_PROTOCOL");
        String masterHost;
        String masterPort;

        String certFile = getEnv(ENV_PREFIX + "CLIENT_CERT_FILE", "KUBERNETES_CLIENT_CERTIFICATE_FILE");

        if (certFile == null) {
            if (protocol == null)
                protocol = "https";

            masterHost = getEnv(ENV_PREFIX + "MASTER_HOST", "KUBERNETES_SERVICE_HOST");
            masterPort = getEnv(ENV_PREFIX + "MASTER_PORT", "KUBERNETES_SERVICE_PORT");
            String saTokenFile = getEnv(ENV_PREFIX + "SA_TOKEN_FILE");
            if (saTokenFile == null)
                saTokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token";

            byte[] bytes = Files.readAllBytes(FileSystems.getDefault().getPath(saTokenFile));
            String saToken = new String(bytes);

            String caCertFile = getEnv(ENV_PREFIX + "CA_CERT_FILE", "KUBERNETES_CA_CERTIFICATE_FILE");
            if (caCertFile == null)
                caCertFile = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";

            // Preemptively add authorization token in headers
            // (TokenStreamProvider does it too, but too late)
            headers.clear();
            headers.put("Authorization", "Bearer " + saToken);
            streamProvider = new TokenStreamProvider(saToken, caCertFile);
        } else {
            // TODO: implement CertificateStreamProvider
            throw new NotImplementedException();
            /*
            if (protocol == null)
                protocol = "http";

            masterHost = getEnv(ENV_PREFIX + "MASTER_HOST", "KUBERNETES_RO_SERVICE_HOST");
            masterPort = getEnv(ENV_PREFIX + "MASTER_PORT", "KUBERNETES_RO_SERVICE_PORT");

            String keyFile = getEnv(ENV_PREFIX + "CLIENT_KEY_FILE", "KUBERNETES_CLIENT_KEY_FILE");
            String keyPassword = getEnv(ENV_PREFIX + "CLIENT_KEY_PASSWORD", "KUBERNETES_CLIENT_KEY_PASSWORD");

            String keyAlgo = getEnv(ENV_PREFIX + "CLIENT_KEY_ALGO", "KUBERNETES_CLIENT_KEY_ALGO");
            if (keyAlgo == null)
                keyAlgo = "RSA";

            String caCertFile = getEnv(ENV_PREFIX + "CA_CERT_FILE", "KUBERNETES_CA_CERTIFICATE_FILE");
            if (caCertFile == null)
                caCertFile = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
            */

            // CertificateStreamProvider isn't implemented yet
            // streamProvider = new CertificateStreamProvider(certFile, keyFile, keyPassword, keyAlgo, caCertFile);
        }

        String ver = getEnv(ENV_PREFIX + "API_VERSION");
        if (ver == null)
            ver = "v1";

        String labels = getEnv(ENV_PREFIX + "LABELS");

        namespace = URLEncoder.encode(namespace, "UTF-8");
        labels = labels == null ? null : URLEncoder.encode(labels, "UTF-8");

        url = String.format("%s://%s:%s/api/%s/namespaces/%s/pods", protocol, masterHost, masterPort, ver, namespace);
        if (labels != null && labels.length() > 0)
            url = url + "?labelSelector=" + labels;
    }

    @Override
    public List<? extends Member> getMembers() throws Exception {

        List<MemberImpl> members = new ArrayList<>();

        InputStream stream = streamProvider.openStream(url, headers, connectionTimeout, readTimeout);
        JSONObject json = new JSONObject(new JSONTokener(stream));

        JSONArray items = json.getJSONArray("items");

        for (int i = 0; i < items.length(); i++) {
            String phase;
            String ip;
            String name;
            Instant creationTime;

            try {
                JSONObject item = items.getJSONObject(i);
                JSONObject status = item.getJSONObject("status");
                phase = status.getString("phase");

                // Ignore shutdown pods
                if (!phase.equals("Running"))
                    continue;

                ip = status.getString("podIP");

                // Get name & start time
                JSONObject metadata = item.getJSONObject("metadata");
                name = metadata.getString("name");
                String timestamp = metadata.getString("creationTimestamp");
                creationTime = Instant.parse(timestamp);
            } catch (JSONException e) {
                log.warn("JSON Exception: ", e);
                continue;
            }

            // We found ourselves, ignore
            if (name.equals(hostName))
                continue;

            // id = md5(hostname)
            byte[] id = md5.digest(name.getBytes());
            long aliveTime = Duration.between(creationTime, startTime).getSeconds() * 1000; // aliveTime is in ms

            MemberImpl member = null;
            try {
                member = new MemberImpl(ip, port, aliveTime);
            } catch (IOException e) {
                // Shouldn't happen:
                // an exception is thrown if hostname can't be resolved to IP, but we already provide an IP
                log.warn("Exception: ", e);
                continue;
            }

            member.setUniqueId(id);
            members.add(member);
        }

        return members;
    }
}
