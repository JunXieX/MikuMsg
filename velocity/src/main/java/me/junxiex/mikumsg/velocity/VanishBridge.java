package me.junxiex.mikumsg.velocity;

import dev.junxiex.mikuvanish.api.MikuVanishAPI;
import dev.junxiex.mikuvanish.api.VanishService;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * MikuVanish 桥接：通过官方对外 API（{@link MikuVanishAPI}）查询玩家隐身状态，
 * 并探测该版本是否提供隐身状态变更事件。
 *
 * <p>MikuVanish 代理端在初始化收尾时把权威服务注册进 {@code MikuVanishAPI}、
 * 代理关停时注销。{@link VanishService#isVanished(UUID)} 读取
 * {@code ConcurrentHashMap}，同步 O(1)、线程安全。</p>
 *
 * <p>对 {@code dev.junxiex.mikuvanish.api} 的引用是 compileOnly——运行时类由
 * MikuVanish 代理端插件的类加载器提供。若未安装 MikuVanish，触碰这些类会抛
 * {@code NoClassDefFoundError}，在 {@link #discover} 中被捕获并降级为"不过滤隐身"。</p>
 *
 * <p>服务实例在 {@link #discover} 时获取并持有：Velocity 不支持单插件热重载，
 * 权威服务只在代理关停时注销，而关停后本插件不再处理事件，缓存实例无失效风险。</p>
 */
public final class VanishBridge {

    /** 状态变更事件类全名（仅用于存在性探测，不直接引用）。 */
    private static final String EVENT_CLASS_NAME = "dev.junxiex.mikuvanish.api.VanishStateChangeEvent";

    private final VanishService service;
    private final boolean stateEventsAvailable;

    private VanishBridge(VanishService service, boolean stateEventsAvailable) {
        this.service = service;
        this.stateEventsAvailable = stateEventsAvailable;
    }

    /**
     * 探测 MikuVanish 官方 API。
     *
     * @return 桥接实例（持有权威服务）；MikuVanish 未安装或未就绪时返回 null
     */
    public static VanishBridge discover(Logger logger) {
        try {
            if (!MikuVanishAPI.isAvailable()) {
                logger.info("未检测到 MikuVanish，隐身玩家的消息隐藏不启用。");
                return null;
            }
            VanishService service = MikuVanishAPI.get();
            boolean stateEvents = probeStateChangeEvent();
            if (stateEvents) {
                logger.info("已接入 MikuVanish 官方 API：隐身玩家的进出/切换消息将被隐藏，"
                        + "进入隐身补发离线消息、解除隐身补发进入消息。");
            } else {
                logger.warn("MikuVanish 版本过旧（缺少隐身状态变更事件），隐身瞬时消息不启用；"
                        + "隐身玩家的进出/切换消息隐藏仍生效。");
            }
            return new VanishBridge(service, stateEvents);
        } catch (LinkageError err) {
            logger.warn("MikuVanish API 类不可用，隐身玩家的消息将无法隐藏。");
            return null;
        }
    }

    /**
     * 探测状态变更事件类是否存在。
     *
     * <p>用 {@code initialize = false} 的 {@link Class#forName} 探测：旧版 MikuVanish
     * 没有该类，直接在自己的代码里引用它会让事件扫描（反射解析方法签名）抛
     * {@code NoClassDefFoundError}。因此调用方必须先经本方法确认，再决定是否注册
     * 事件监听器。</p>
     */
    private static boolean probeStateChangeEvent() {
        try {
            Class.forName(EVENT_CLASS_NAME, false, MikuVanishAPI.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** 该 MikuVanish 版本是否提供隐身状态变更事件（旧版本没有）。 */
    public boolean supportsStateEvents() {
        return stateEventsAvailable;
    }

    /** 玩家当前是否处于隐身。 */
    public boolean isVanished(UUID playerId) {
        return service.isVanished(playerId);
    }
}