package xyz.shadowmere.desktop;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/** Platform-neutral full-device control. Application proxy mode never invokes this broker. */
public final class DesktopTunnel implements AutoCloseable {
    public record Status(boolean active,boolean blocked,String message) { }
    private final LinuxTunnel linux;
    private final NativeDesktopTunnel nativeTunnel;
    public DesktopTunnel(Path data,Consumer<String> log) {
        boolean onLinux=platform().equals("Linux");
        linux=onLinux?new LinuxTunnel(data,log):null;
        nativeTunnel=onLinux?null:new NativeDesktopTunnel(data,log);
    }
    public static String platform() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if(os.startsWith("windows"))return "Windows";
        if(os.contains("mac"))return "macOS";
        return os.contains("linux")?"Linux":"Unsupported platform";
    }
    public static boolean supported(){return platform().equals("Linux")?LinuxTunnel.supported():NativeDesktopTunnel.supported();}
    public static String requirements() {
        return switch(platform()) {
            case "Linux" -> "Requires Linux x64, PolicyKit, nftables, iproute2 and /dev/net/tun.";
            case "Windows" -> "Requires Windows x64 or ARM64, PowerShell and administrator approval. Full-device routing and strict blocking use an elevated helper.";
            case "macOS" -> "Requires Intel or Apple Silicon macOS, a root-owned Python 3 installation and administrator approval. Strict blocking requires the stock Apple PF filter layout.";
            default -> "Full-device routing is unavailable on this platform.";
        };
    }
    public static Status status() {
        if(platform().equals("Linux")){var s=LinuxTunnel.status();return new Status(s.active(),s.blocked(),s.message());}
        return NativeDesktopTunnel.status();
    }
    public void start(Server entry,Server exit,boolean strict)throws Exception {if(linux!=null)linux.start(entry,exit,strict);else nativeTunnel.start(entry,exit,strict);}
    public void stop(){if(linux!=null)linux.stop();else nativeTunnel.stop();}
    public void release()throws Exception {if(linux!=null)linux.release();else nativeTunnel.release();}
    public boolean isAlive(){return linux!=null?linux.isAlive():nativeTunnel.isAlive();}
    @Override public void close(){stop();}
}
