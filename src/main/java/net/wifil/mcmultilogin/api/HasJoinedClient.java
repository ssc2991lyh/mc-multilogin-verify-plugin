package net.wifil.mcmultilogin.api;

import java.io.IOException;
import java.net.InetAddress;

/**
 * 统一的「hasJoined 验证」客户端接口。
 *
 * <p>两种实现返回完全同形状的 {@link LoginApiClient.ApiResult}：
 * <ul>
 *   <li>{@link LoginApiClient}：调用外部 MC-MultiLogin-service（HTTP 客户端模式）。</li>
 *   <li>{@code VerifyService}：插件内嵌的验证（合并 service 后的自包含模式）。</li>
 * </ul>
 * 这样 {@link net.wifil.mcmultilogin.session.LoginSessionHandler} 无需关心验证来自外部还是内部，
 * 只消费 {@code ApiResult}（200/403/204）。</p>
 */
public interface HasJoinedClient {

    /**
     * 执行一次 hasJoined 验证。
     *
     * @return 验证结果；返回 {@code null} 表示「本客户端未启用」，调用方应 fail-open 退回原版验证。
     * @throws IOException 网络层错误（由调用方统一 fail-open）
     */
    LoginApiClient.ApiResult hasJoined(String username, String serverId, InetAddress address,
                                        boolean detail) throws IOException;
}
