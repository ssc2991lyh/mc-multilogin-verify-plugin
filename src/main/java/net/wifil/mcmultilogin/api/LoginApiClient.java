package net.wifil.mcmultilogin.api;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调用 MC-MultiLogin-service 的 {@code /sessionserver/session/minecraft/hasJoined} 接口，
 * 固定带上 {@code detail=true} 以拿到详细的失败原因。
 *
 * <p><b>与 Fabric 版的差异（重要）</b>：原版用的是 {@code java.net.http.HttpClient}，
 * 那是 Java 11+ 才有的 API。本插件为了「一个 JAR 兼容全版本」把字节码目标定在 Java 8
 * （老服务器如 1.8~1.16 常年跑在 Java 8 上），因此这里改用 JDK 1.0 时代就存在、
 * 至今仍完全可用的 {@link HttpURLConnection}。行为保持一致：GET、超时可配、
 * 返回状态码 + 响应体原文。</p>
 *
 * <p>同理，{@code ApiResult} 原本是 record（Java 16+），这里降级为普通不可变类，
 * 但保留了 {@code statusCode()} / {@code body()} 这种 record 风格的读取方法名，
 * 让调用方代码无需改动。</p>
 */
public class LoginApiClient implements HasJoinedClient {

    private static final Gson GSON = new Gson();

    /** 统计：累计请求数 / 成功数 / 失败数，供 /multilogin status 展示。 */
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong OK = new AtomicLong();
    private static final AtomicLong DENIED = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();

    private final String apiBase;
    private final int timeoutMillis;

    /**
     * @param apiBase        认证服务根地址，例如 {@code https://auth.example.com}
     * @param timeoutSeconds 连接与读取超时（秒）
     */
    public LoginApiClient(String apiBase, int timeoutSeconds) {
        // 去掉结尾斜杠，保证后面拼 URL 时不会出现双斜杠。
        String base = apiBase == null ? "" : apiBase.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.apiBase = base;
        this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
    }

    public String apiBase() {
        return apiBase;
    }

    /**
     * 请求 hasJoined。
     *
     * @return {@link ApiResult}：200 时 body 是 GameProfile 的 JSON 原文；
     *         403 时 body 是 {@link ErrorResponse} 的 JSON。
     * @throws IOException 网络层错误
     */
    public ApiResult hasJoined(String username, String serverId, InetAddress address,
                               boolean detail) throws IOException {
        StringBuilder url = new StringBuilder(apiBase)
                .append("/sessionserver/session/minecraft/hasJoined?username=")
                .append(encode(username))
                .append("&serverId=")
                .append(encode(serverId));

        if (address != null) {
            url.append("&ip=").append(encode(address.getHostAddress()));
        }
        if (detail) {
            url.append("&detail=true");
        }

        TOTAL.incrementAndGet();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MCMultiLoginCompat-Bukkit");

            int status = conn.getResponseCode();
            // 4xx/5xx 时 getInputStream() 会抛异常，必须读 getErrorStream()，
            // 否则拿不到 403 的详细错误体 —— 这正是本插件的核心信息来源。
            InputStream in = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);

            if (status == 200) {
                OK.incrementAndGet();
            } else if (status == 403) {
                DENIED.incrementAndGet();
            }
            return new ApiResult(status, body);
        } catch (IOException e) {
            ERRORS.incrementAndGet();
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 把 403 响应体解析成 {@link ErrorResponse}。永不返回 null。
     * 供内嵌 {@code VerifyService}（同样产出 ErrorResponse 形状的 403 体）与外部服务共用。
     */
    public static ErrorResponse parseError(String body) {
        try {
            ErrorResponse err = GSON.fromJson(body, ErrorResponse.class);
            return err != null ? err : new ErrorResponse();
        } catch (RuntimeException e) {
            // 服务端返回了非 JSON（例如网关的 HTML错误页）时不能炸，返回空对象即可。
            return new ErrorResponse();
        }
    }

    /** 统计行，供状态命令展示。 */
    public static String statsLine() {
        return "请求 " + TOTAL.get() + " 次（通过 " + OK.get()
                + " / 拒绝 " + DENIED.get() + " / 异常 " + ERRORS.get() + "）";
    }

    // ------------------------------------------------------------------

    private static String encode(String s) {
        try {
            // Java 8 的 URLEncoder 只有 String 编码名这个重载，
            // 带 Charset 参数的重载是 Java 10+ 才加的，这里必须用字符串形式。
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 是 JVM 必须支持的编码，理论上不可能走到这里。
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
                // 关闭失败无关紧要。
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /**
     * HTTP 结果载体。Java 8 版：普通不可变类代替 record，方法名沿用 record 风格。
     */
    public static final class ApiResult {

        private final int statusCode;
        private final String body;

        public ApiResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }

        public int statusCode() {
            return statusCode;
        }

        public String body() {
            return body;
        }

        public boolean isSuccess() {
            return statusCode == 200;
        }

        public boolean isForbidden() {
            return statusCode == 403;
        }

        @Override
        public String toString() {
            return "ApiResult{status=" + statusCode + ", bodyLength=" + body.length() + '}';
        }
    }
}
