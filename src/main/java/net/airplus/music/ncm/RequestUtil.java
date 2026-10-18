// skid SetsunaClient
package net.airplus.music.ncm;

import com.google.gson.JsonObject;
import net.airplus.music.ncm.util.json.JsonUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 网易云音乐API请求工具类
 */
public class RequestUtil {

    private static final SecureRandom random = new SecureRandom();

    private static final Map<String, OsConfig> OS_MAP = new HashMap<>();

    public static String globalDeviceId = "";

    private static final Map<String, Map<String, String>> USER_AGENT_MAP = new HashMap<>();

    static {
        Map<String, String> weapiUA = new HashMap<>();
        weapiUA.put("pc", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0");
        USER_AGENT_MAP.put("weapi", weapiUA);

        Map<String, String> linuxapiUA = new HashMap<>();
        linuxapiUA.put("linux", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36");
        USER_AGENT_MAP.put("linuxapi", linuxapiUA);

        Map<String, String> apiUA = new HashMap<>();
        apiUA.put("pc", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152");
        apiUA.put("android", "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0 (Linux; U; Android 14; 23013RK75C Build/UKQ1.230804.001)");
        apiUA.put("iphone", "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)");
        USER_AGENT_MAP.put("api", apiUA);
    }


    public static class AppConfig {
        private String domain;
        private String apiDomain;
        private boolean encrypt;
        private boolean encryptResponse;

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getApiDomain() {
            return apiDomain;
        }

        public void setApiDomain(String apiDomain) {
            this.apiDomain = apiDomain;
        }

        public boolean isEncrypt() {
            return encrypt;
        }

        public void setEncrypt(boolean encrypt) {
            this.encrypt = encrypt;
        }

        public boolean isEncryptResponse() {
            return encryptResponse;
        }

        public void setEncryptResponse(boolean encryptResponse) {
            this.encryptResponse = encryptResponse;
        }

        public static AppConfigBuilder builder() {
            return new AppConfigBuilder();
        }

        public static class AppConfigBuilder {
            private String domain;
            private String apiDomain;
            private boolean encrypt;
            private boolean encryptResponse;

            public AppConfigBuilder domain(String domain) {
                this.domain = domain;
                return this;
            }

            public AppConfigBuilder apiDomain(String apiDomain) {
                this.apiDomain = apiDomain;
                return this;
            }

            public AppConfigBuilder encrypt(boolean encrypt) {
                this.encrypt = encrypt;
                return this;
            }

            public AppConfigBuilder encryptResponse(boolean encryptResponse) {
                this.encryptResponse = encryptResponse;
                return this;
            }

            public AppConfig build() {
                AppConfig config = new AppConfig();
                config.domain = this.domain;
                config.apiDomain = this.apiDomain;
                config.encrypt = this.encrypt;
                config.encryptResponse = this.encryptResponse;
                return config;
            }
        }
    }

    private static final AppConfig APP_CONF = AppConfig.builder()
            .domain("https://music.163.com")
            .apiDomain("https://interfacepc.music.163.com")
            .encrypt(true)
            .encryptResponse(false)
            .build();

    static {
        OS_MAP.put("pc", OsConfig.builder()
                .os("pc")
                .appver("3.1.12.204072")
                .osver("Microsoft-Windows-11-Professional-build-26100-64bit")
                .channel("netease")
                .build());

        OS_MAP.put("linux", OsConfig.builder()
                .os("linux")
                .appver("1.2.1.0428")
                .osver("Deepin 20.9")
                .channel("netease")
                .build());

        OS_MAP.put("android", OsConfig.builder()
                .os("android")
                .appver("8.20.20.231215173437")
                .osver("14")
                .channel("xiaomi")
                .build());

        OS_MAP.put("iphone", OsConfig.builder()
                .os("iPhone OS")
                .appver("9.0.90")
                .osver("16.2")
                .channel("distribution")
                .build());
    }

    public static class OsConfig {
        private String os;
        private String appver;
        private String osver;
        private String channel;

        public String getOs() {
            return os;
        }

        public void setOs(String os) {
            this.os = os;
        }

        public String getAppver() {
            return appver;
        }

        public void setAppver(String appver) {
            this.appver = appver;
        }

        public String getOsver() {
            return osver;
        }

        public void setOsver(String osver) {
            this.osver = osver;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public static OsConfigBuilder builder() {
            return new OsConfigBuilder();
        }

        public static class OsConfigBuilder {
            private String os;
            private String appver;
            private String osver;
            private String channel;

            public OsConfigBuilder os(String os) {
                this.os = os;
                return this;
            }

            public OsConfigBuilder appver(String appver) {
                this.appver = appver;
                return this;
            }

            public OsConfigBuilder osver(String osver) {
                this.osver = osver;
                return this;
            }

            public OsConfigBuilder channel(String channel) {
                this.channel = channel;
                return this;
            }

            public OsConfig build() {
                OsConfig config = new OsConfig();
                config.os = this.os;
                config.appver = this.appver;
                config.osver = this.osver;
                config.channel = this.channel;
                return config;
            }
        }
    }

    public static class RequestOptions {
        private Map<String, String> headers;
        private String realIP;
        private String ip;
        private Object cookie;
        private String crypto;
        private String ua;
        private String proxy;
        /**
         * 如果为 null 的话则使用 APP_CONF 的默认值
         */
        private Boolean encryptedResponse;

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getRealIP() {
            return realIP;
        }

        public void setRealIP(String realIP) {
            this.realIP = realIP;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public Object getCookie() {
            return cookie;
        }

        public void setCookie(Object cookie) {
            this.cookie = cookie;
        }

        public String getCrypto() {
            return crypto;
        }

        public void setCrypto(String crypto) {
            this.crypto = crypto;
        }

        public String getUa() {
            return ua;
        }

        public void setUa(String ua) {
            this.ua = ua;
        }

        public String getProxy() {
            return proxy;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy;
        }

        public Boolean getEncryptedResponse() {
            return encryptedResponse;
        }

        public void setEncryptedResponse(Boolean encryptedResponse) {
            this.encryptedResponse = encryptedResponse;
        }

        public static RequestOptionsBuilder builder() {
            return new RequestOptionsBuilder();
        }

        public static class RequestOptionsBuilder {
            private Map<String, String> headers;
            private String realIP;
            private String ip;
            private Object cookie;
            private String crypto;
            private String ua;
            private String proxy;
            private Boolean encryptedResponse;

            public RequestOptionsBuilder headers(Map<String, String> headers) {
                this.headers = headers;
                return this;
            }

            public RequestOptionsBuilder realIP(String realIP) {
                this.realIP = realIP;
                return this;
            }

            public RequestOptionsBuilder ip(String ip) {
                this.ip = ip;
                return this;
            }

            public RequestOptionsBuilder cookie(Object cookie) {
                this.cookie = cookie;
                return this;
            }

            public RequestOptionsBuilder crypto(String crypto) {
                this.crypto = crypto;
                return this;
            }

            public RequestOptionsBuilder ua(String ua) {
                this.ua = ua;
                return this;
            }

            public RequestOptionsBuilder proxy(String proxy) {
                this.proxy = proxy;
                return this;
            }

            public RequestOptionsBuilder encryptedResponse(Boolean encryptedResponse) {
                this.encryptedResponse = encryptedResponse;
                return this;
            }

            public RequestOptions build() {
                RequestOptions options = new RequestOptions();
                options.headers = this.headers;
                options.realIP = this.realIP;
                options.ip = this.ip;
                options.cookie = this.cookie;
                options.crypto = this.crypto;
                options.ua = this.ua;
                options.proxy = this.proxy;
                options.encryptedResponse = this.encryptedResponse;
                return options;
            }
        }
    }

    public static class RequestAnswer {
        private int status;
        private Object body;
        private String[] cookies;

        RequestAnswer(int status, Object body, String[] cookies) {
            this.status = status;
            this.body = body;
            this.cookies = cookies;
        }

        public static RequestAnswerBuilder builder() {
            return new RequestAnswerBuilder();
        }

        public static class RequestAnswerBuilder {
            private int status;
            private Object body;
            private String[] cookies;

            public RequestAnswerBuilder status(int status) {
                this.status = status;
                return this;
            }

            public RequestAnswerBuilder body(Object body) {
                this.body = body;
                return this;
            }

            public RequestAnswerBuilder cookies(String[] cookies) {
                this.cookies = cookies;
                return this;
            }

            public RequestAnswer build() {
                return new RequestAnswer(this.status, this.body, this.cookies);
            }
        }

        public static RequestAnswer of(JsonObject object, int status, String[] cookies) {
            return new RequestAnswer(status, object, cookies);
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public Object getBody() {
            return body;
        }

        public void setBody(Object body) {
            this.body = body;
        }

        public String[] getCookies() {
            return cookies;
        }

        public void setCookies(String[] cookies) {
            this.cookies = cookies;
        }

        public String toString() {
            return JsonUtils.toJsonString(this.body);
        }

        public JsonObject toJsonObject() {
            return JsonUtils.parse(toString(), JsonObject.class);
        }
    }

    /**
     * 生成WNMCID
     */
    private static String generateWNMCID() {
        String characters = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder randomString = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            randomString.append(characters.charAt(random.nextInt(characters.length())));
        }
        return randomString + "." + System.currentTimeMillis() + ".01.0";
    }

    /**
     * 生成随机字符串
     */
    private static String generateRandomString(int length) {
        byte[] bytes = generateRandomBytes(length);
        return bytesToHex(bytes);
    }

    public static byte[] generateRandomBytes(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * 选择User-Agent
     */
    private static String chooseUserAgent(String crypto, String uaType) {
        if (uaType == null) uaType = "pc";
        Map<String, String> cryptoMap = USER_AGENT_MAP.get(crypto);
        if (cryptoMap != null) {
            return cryptoMap.getOrDefault(uaType, "");
        }
        return "";
    }

    /**
     * Cookie字符串转Map
     */
    private static Map<String, String> cookieToMap(String cookieStr) {
        Map<String, String> cookieMap = new HashMap<>();
        if (StringUtils.isNotBlank(cookieStr)) {
            String[] pairs = cookieStr.split(";\\s*");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    cookieMap.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        return cookieMap;
    }

    /**
     * Cookie Map转字符串
     */
    private static String cookieMapToString(Map<String, String> cookieMap) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            if (sb.length() != 0) {
                sb.append("; ");
            }
            sb.append(urlEncode(entry.getKey())).append("=").append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 构建表单数据
     */
    private static String buildFormData(Map<String, String> formData) {
        return formData.entrySet().stream()
                .map(entry -> {
                    try {
                        return URLEncoder.encode(entry.getKey(), "UTF-8") +
                                "=" + URLEncoder.encode(entry.getValue(), "UTF-8");
                    } catch (Exception e) {
                        return entry.getKey() + "=" + entry.getValue();
                    }
                })
                .collect(Collectors.joining("&"));
    }

    /**
     * 创建HTTP请求
     */
    @SuppressWarnings("unchecked")
    public static RequestAnswer createRequest(String uri, Object data, RequestOptions options) {
        try {
            Map<String, String> headers = options.getHeaders() != null ?
                    new HashMap<String, String>(options.getHeaders()) : new HashMap<String, String>();

            String ip = StringUtils.isNotBlank(options.getRealIP()) ?
                    options.getRealIP() : options.getIp();

            if (StringUtils.isNotBlank(ip)) {
                headers.put("X-Real-IP", ip);
                headers.put("X-Forwarded-For", ip);
            }

            Map<String, String> cookie = new HashMap<>();
            if (options.getCookie() instanceof String) {
                cookie = cookieToMap((String) options.getCookie());
            } else if (options.getCookie() instanceof Map) {
                cookie = (Map<String, String>) options.getCookie();
            }

            String nuid = generateRandomString(32);
            OsConfig osConfig = OS_MAP.getOrDefault(cookie.get("os"), OS_MAP.get("pc"));

            cookie.putIfAbsent("__remember_me", "true");
            cookie.putIfAbsent("ntes_kaola_ad", "1");
            cookie.putIfAbsent("_ntes_nuid", nuid);
            cookie.putIfAbsent("_ntes_nnid", nuid + "," + System.currentTimeMillis());
            cookie.putIfAbsent("WNMCID", generateWNMCID());
            cookie.putIfAbsent("WEVNSM", "1.0.0");
            cookie.putIfAbsent("osver", osConfig.getOsver());
            cookie.putIfAbsent("deviceId", generateRandomString(26).toUpperCase() + "\\r");
            cookie.putIfAbsent("os", osConfig.getOs());
            cookie.putIfAbsent("channel", osConfig.getChannel());
            cookie.putIfAbsent("appver", osConfig.getAppver());

            if (!uri.contains("login")) {
                cookie.put("NMTID", generateRandomString(16));
            }

            headers.put("Cookie", cookieMapToString(cookie));

            String crypto = StringUtils.isNotBlank(options.getCrypto()) ?
                    options.getCrypto() : (APP_CONF.isEncrypt() ? "eapi" : "api");

            String url = "";
            String postData = "";
            String csrfToken = cookie.getOrDefault("__csrf", "");

            switch (crypto) {
                case "weapi":
                    headers.put("Referer", APP_CONF.getDomain());
                    headers.put("User-Agent", StringUtils.isNotBlank(options.getUa()) ?
                            options.getUa() : chooseUserAgent("weapi", "pc"));

                    Map<String, Object> weapiData = new HashMap<>();
                    if (data instanceof Map) {
                        weapiData.putAll((Map<String, Object>) data);
                    }
                    weapiData.put("csrf_token", csrfToken);

                    CryptoUtil.WeapiResult weapiResult = CryptoUtil.weapi(weapiData);
                    url = APP_CONF.getDomain() + "/weapi/" + uri.substring(5);

                    Map<String, String> weapiFormData = new HashMap<>();
                    weapiFormData.put("params", weapiResult.getParams());
                    weapiFormData.put("encSecKey", weapiResult.getEncSecKey());
                    postData = buildFormData(weapiFormData);
                    break;

                case "linuxapi":
                    headers.put("User-Agent", StringUtils.isNotBlank(options.getUa()) ?
                            options.getUa() : chooseUserAgent("linuxapi", "linux"));

                    Map<String, Object> linuxApiRequest = new HashMap<>();
                    linuxApiRequest.put("method", "POST");
                    linuxApiRequest.put("url", APP_CONF.getDomain() + uri);
                    linuxApiRequest.put("params", data);

                    CryptoUtil.LinuxapiResult linuxResult = CryptoUtil.linuxapi(linuxApiRequest);
                    url = APP_CONF.getDomain() + "/api/linux/forward";

                    Map<String, String> linuxFormData = new HashMap<>();
                    linuxFormData.put("eparams", linuxResult.getEparams());
                    postData = buildFormData(linuxFormData);
                    break;

                case "eapi":
                case "api":
                    Map<String, String> header = new HashMap<>();
                    header.put("osver", cookie.get("osver"));
                    header.put("deviceId", cookie.get("deviceId"));
                    header.put("os", cookie.get("os"));
                    header.put("appver", cookie.get("appver"));
                    header.put("versioncode", cookie.getOrDefault("versioncode", "140"));
                    header.put("mobilename", cookie.getOrDefault("mobilename", ""));
                    header.put("buildver", cookie.getOrDefault("buildver",
                            String.valueOf(System.currentTimeMillis()).substring(0, 10)));
                    header.put("resolution", cookie.getOrDefault("resolution", "1920x1080"));
                    header.put("__csrf", csrfToken);
                    header.put("channel", cookie.get("channel"));
                    header.put("requestId", System.currentTimeMillis() + "_" +
                            String.format("%04d", random.nextInt(1000)));

                    if (cookie.containsKey("MUSIC_U")) {
                        header.put("MUSIC_U", cookie.get("MUSIC_U"));
                    }
                    if (cookie.containsKey("MUSIC_A")) {
                        header.put("MUSIC_A", cookie.get("MUSIC_A"));
                    }

                    String value = cookieMapToString(header);
                    headers.put("Cookie", value);
                    headers.put("User-Agent", StringUtils.isNotBlank(options.getUa()) ?
                            options.getUa() : chooseUserAgent("api", "pc"));

                    if ("eapi".equals(crypto)) {
                        Map<String, Object> eapiData = new HashMap<>();
                        if (data instanceof Map) {
                            eapiData.putAll((Map<String, Object>) data);
                        }
                        eapiData.put("header", header);

                        boolean eR = options.getEncryptedResponse() != null ? options.getEncryptedResponse() : APP_CONF.isEncryptResponse();
                        eapiData.put("e_r", eR);

                        CryptoUtil.EapiResult eapiResult = CryptoUtil.eapi(uri, eapiData);
                        url = APP_CONF.getApiDomain() + "/eapi/" + uri.substring(5);

                        Map<String, String> eapiFormData = new HashMap<>();
                        eapiFormData.put("params", eapiResult.getParams());
                        postData = buildFormData(eapiFormData);
                    } else {
                        url = APP_CONF.getApiDomain() + uri;
                        postData = JsonUtils.toJsonString(data);
                    }
                    break;

                default:
                    System.err.println("Unknown Crypto: " + crypto);
                    throw new IllegalArgumentException("Unknown crypto type: " + crypto);
            }

            URL requestUrl = new URL(url);
            HttpURLConnection connection = null;

            if (StringUtils.isNotBlank(options.getProxy())) {
                String[] proxyParts = options.getProxy().split(":");
                if (proxyParts.length == 2) {
                    String proxyHost = proxyParts[0];
                    int proxyPort = Integer.parseInt(proxyParts[1]);
                    Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                    connection = (HttpURLConnection) requestUrl.openConnection(proxy);
                } else {
                    connection = (HttpURLConnection) requestUrl.openConnection();
                }
            } else {
                connection = (HttpURLConnection) requestUrl.openConnection();
            }

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setDoInput(true);

            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }

            if (postData != null && !postData.isEmpty()) {
                OutputStream os = connection.getOutputStream();
                try {
                    byte[] input = postData.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                } finally {
                    os.close();
                }
            }

            int responseCode = connection.getResponseCode();

            String responseBody = "";
            InputStream inputStream = null;
            try {
                if (responseCode >= 200 && responseCode < 300) {
                    inputStream = connection.getInputStream();
                } else {
                    inputStream = connection.getErrorStream();
                }

                if (inputStream != null) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    try {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        responseBody = response.toString();
                    } finally {
                        reader.close();
                    }
                }
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
            }

            RequestAnswer answer = RequestAnswer.builder()
                    .status(responseCode)
                    .build();

            if (!responseBody.isEmpty()) {
                if (Boolean.TRUE.equals(options.getEncryptedResponse()) && "eapi".equals(crypto)) {
                    try {
                        answer.setBody(CryptoUtil.eapiResDecrypt(responseBody));
                    } catch (Exception e) {
                        answer.setBody(JsonUtils.parse(responseBody, Object.class));
                    }
                } else {
                    try {
                        answer.setBody(JsonUtils.parse(responseBody, Object.class));
                    } catch (Exception e) {
                        answer.setBody(responseBody);
                    }
                }
            }

            Map<String, List<String>> headerFields = connection.getHeaderFields();
            List<String> setCookieHeaders = headerFields.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (setCookieHeaders != null && !setCookieHeaders.isEmpty()) {
                answer.setCookies(setCookieHeaders.toArray(new String[0]));
            }

            connection.disconnect();

            return answer;

        } catch (Exception e) {
            System.err.println("Request failed");
            e.printStackTrace();

            Map<String, Object> map = new HashMap<>();
            map.put("code", 502);
            map.put("msg", e.getMessage());

            return RequestAnswer.builder()
                    .status(502)
                    .body(map)
                    .build();
        }
    }
}
