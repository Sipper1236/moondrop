import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/** Build-only tool: explicit inputs keep Java runtimes and local state out of the download. */
class PortablePackage {
    public static void main(String[] args) throws Exception {
        Map<String,Path> inputs=new LinkedHashMap<>();
        inputs.put("moondrop.jar",Path.of("target/moondrop.jar"));
        for(String name:new String[]{"README.md","INSTALL.md","LICENSE","THIRD_PARTY.md","WINDOWS-TUNNEL.md","MACOS-TUNNEL.md"}) inputs.put(name,Path.of(name));
        for(String name:new String[]{"moondrop.desktop","moondrop.svg"}) inputs.put(name,Path.of(name));
        for(String name:new String[]{"launch-windows.bat","launch-macos.command","installmacos.command","launch-linux.sh","install-linux.sh"}) inputs.put(name,Path.of("scripts/launchers",name));
        Files.createDirectories(Path.of("dist"));
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(Files.newOutputStream(Path.of("dist/Moondrop.zip")))) {
            for(var input:inputs.entrySet()) {
                ZipArchiveEntry entry=new ZipArchiveEntry("Moondrop/"+input.getKey());
                entry.setUnixMode(input.getKey().endsWith(".command")||input.getKey().endsWith(".sh")?0755:0644);
                zip.putArchiveEntry(entry);Files.copy(input.getValue(),zip);zip.closeArchiveEntry();
            }
        }
        System.out.println("Created portable ZIP with "+inputs.size()+" files; Java is not bundled.");
    }
}
