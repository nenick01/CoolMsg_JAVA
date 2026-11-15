package coolmsg.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import coolmsg.common.model.OrgTree;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.server.config.OrgChart;

/**
 * 구현 6단계 테스트: 서버 조직도 (설계서 2.10, 3.2, 3.5, 5장 U54·U55). ServerTest.main에서 불린다.
 */
final class OrgTest {

    private static BiConsumer<String, Boolean> check;

    private OrgTest() {
    }

    static void run(BiConsumer<String, Boolean> checker, Consumer<String> section) throws Exception {
        check = checker;
        section.accept("OrgChart - org.txt 읽기·저장·사용자 빼기 (3.2, U55)");
        orgChart();
    }

    private static void orgChart() throws Exception {
        Path dir = Files.createTempDirectory("coolmsg-org");
        Path file = dir.resolve("org.txt");
        OrgChart missing = new OrgChart(file);
        missing.load(id -> true);
        check.accept("파일 없음 → 조직도 없음(ORG_NONE용 null), 경고, 서버는 계속",
                !missing.exists() && missing.packetText(id -> true) == null && missing.getWarnings().size() == 1);

        Files.writeString(file, "# 맨 위 주석\n# 두 번째 줄\n\nD|학교\nD|학교>교무실\nU|학교>교무실|kim\nU|학교>교무실|ghost\n"
                + "U|학교>교무실|old\nU|학교|lee\n", StandardCharsets.UTF_8);
        OrgChart chart = new OrgChart(file);
        chart.load(id -> !id.equals("ghost") && !id.equals("old")); // ghost: 없는 사용자, old: 삭제된 사용자
        check.accept("없는·삭제된 아이디는 읽을 때 건너뛰고 경고", chart.getWarnings().size() == 2
                && chart.deptNameOf("ghost") == null && chart.deptNameOf("kim").equals("교무실"));
        check.accept("ORG_OK 데이터: 주석 없이, 활성 사용자만",
                chart.packetText(id -> !id.equals("lee")).equals("D|학교\nD|학교>교무실\nU|학교>교무실|kim\n"));

        OrgTree edited = chart.snapshot();
        edited.getRoot().getChildren().add(OrgTree.Node.dept("행정실"));
        chart.save(edited);
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        check.accept("저장: 맨 위 주석 보존, 새 부서 반영, 메모리도 바로 바뀜",
                saved.startsWith("# 맨 위 주석\n# 두 번째 줄\n") && saved.contains("D|학교>행정실")
                        && chart.packetText(id -> true).contains("D|학교>행정실"));
        check.accept("사용자 삭제 → 조직도에서 빼고 바로 저장 (U55)",
                chart.removeUser("kim") && chart.deptNameOf("kim") == null
                        && !Files.readString(file, StandardCharsets.UTF_8).contains("|kim"));
        check.accept("조직도에 없던 사용자 삭제 → 저장하지 않음", !chart.removeUser("nobody"));

        Files.writeString(file, "# 부서 없음\nU|학교|kim\n", StandardCharsets.UTF_8);
        OrgChart empty = new OrgChart(file);
        empty.load(id -> true);
        check.accept("부서가 하나도 없음 → 조직도 없음(ORG_NONE)", empty.packetText(id -> true) == null);

        Path broken = dir.resolve("broken.txt");
        Files.write(broken, new byte[] {(byte) 0xC3, (byte) 0x28, '\n'});
        OrgChart unreadable = new OrgChart(broken);
        unreadable.load(id -> true);
        check.accept("UTF-8이 아닌 파일 → 경고만 남기고 조직도 없음 (서버는 계속, 3.7)",
                !unreadable.exists() && unreadable.getWarnings().size() == 1);
    }

    static Packet orgRequest(int reqId) {
        return new Packet(PacketType.ORG).setReqId(reqId);
    }

    static List<String> lines(String text) {
        return List.of(text.split("\n"));
    }
}
