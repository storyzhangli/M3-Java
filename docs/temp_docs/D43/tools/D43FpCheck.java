import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
public class D43FpCheck {
    public static void main(String[] a) throws Exception {
        for (String f : a) {
            List<String> full = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(f))) {
                if (line.startsWith("CANON ")) full.add(line.substring(6).trim());
            }
            Collections.sort(full);
            List<String> noId1 = new ArrayList<>();
            for (String l : full) noId1.add(l.substring(l.indexOf('|')+1));
            String v1 = sha(String.join("\n", noId1));                       // 现 probe 口径（排序后保持相对序）
            List<String> noId2 = new ArrayList<>(noId1); Collections.sort(noId2);
            String v2 = sha(String.join("\n", noId2));                        // 去首列后再排序
            System.out.println(Path.of(f).getFileName() + " lines=" + full.size());
            System.out.println("  V1_keepOrderAfterStrip=" + v1);
            System.out.println("  V2_resortAfterStrip   =" + v2);
        }
    }
    static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
