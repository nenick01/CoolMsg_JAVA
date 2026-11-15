package coolmsg.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import coolmsg.common.file.AtomicFile;
import coolmsg.common.file.HeaderBodyFile;
import coolmsg.common.file.HeaderFormat;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.model.AttachmentInfo;
import coolmsg.common.model.DeliveryState;
import coolmsg.common.model.HtmlText;
import coolmsg.common.model.MessageData;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;

/**
 * Common 프로젝트 테스트. 외부 라이브러리(JUnit) 없이 main으로 실행한다.
 * 결과는 docs/04_테스트기록.md에 기록한다.
 *
 * 실행: VS Code "Test: Common" 또는 Eclipse에서 이 클래스를 Java Application으로 실행
 */
public class CommonTest {

    private static int passed = 0;
    private static int failed = 0;

    /** 예외를 던질 수 있는 코드 조각. */
    private interface Action {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        headerFormat();
        packet();
        packetReadWrite();
        headerBodyFile();
        atomicFile();
        timeFormat();
        model();
        htmlText();

        System.out.println();
        System.out.println("결과: 통과 " + passed + " / 실패 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ===================================================================
    // HeaderFormat (설계서 5장 U1)
    // ===================================================================
    private static void headerFormat() throws Exception {
        section("HeaderFormat - 헤더 규칙 U1");

        Map<String, String> m = new LinkedHashMap<>();
        m.put("b", "2");
        m.put("a", "1");
        m.put("reason", "x=y=z");
        String text = HeaderFormat.format(m);
        check("쓰기: 순서 유지, 줄마다 \\n", text.equals("b=2\na=1\nreason=x=y=z\n"));
        Map<String, String> back = HeaderFormat.parse(text);
        check("읽기: 다시 읽으면 같은 순서·같은 값", back.equals(m) && back.keySet().iterator().next().equals("b"));
        check("값 안의 '='는 그대로 값 (첫 '='에서만 나눔)", "x=y=z".equals(back.get("reason")));
        check("빈 문자열은 빈 맵", HeaderFormat.parse("").isEmpty());

        expectThrows("같은 키 두 번 → 형식 오류", FormatException.class, () -> HeaderFormat.parse("a=1\na=2\n"));
        expectThrows("\\r\\n 줄바꿈 → 형식 오류", FormatException.class, () -> HeaderFormat.parse("a=1\r\n"));
        expectThrows("'=' 없는 줄 → 형식 오류", FormatException.class, () -> HeaderFormat.parse("abc\n"));
        expectThrows("키가 빈 줄(=값) → 형식 오류", FormatException.class, () -> HeaderFormat.parse("=1\n"));
        expectThrows("중간에 빈 줄 → 형식 오류", FormatException.class, () -> HeaderFormat.parse("a=1\n\nb=2\n"));
        expectThrows("마지막 줄에 \\n 없음 → 형식 오류", FormatException.class, () -> HeaderFormat.parse("a=1"));

        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("a", "줄\n바꿈");
        expectThrows("값에 줄바꿈 → 쓰기 전에 예외", IllegalArgumentException.class, () -> HeaderFormat.format(bad));
        expectThrows("깨진 UTF-8 → 형식 오류", FormatException.class,
                () -> HeaderFormat.decodeUtf8(new byte[] {(byte) 0xC3, (byte) 0x28}));
    }

    // ===================================================================
    // Packet
    // ===================================================================
    private static void packet() throws Exception {
        section("Packet - 헤더 검사, 응답, 로그");

        Packet p = new Packet(PacketType.LOGIN).setReqId(3).put("userId", "kim").put("password", "secret");
        check("getType / getReqId", p.getType() == PacketType.LOGIN && p.getReqId() == 3);
        check("toString에서 비밀번호 가림", p.toString().contains("password=****") && !p.toString().contains("secret"));

        Packet reply = Packet.replyTo(p, PacketType.LOGIN_FAIL);
        check("replyTo: 요청의 reqId를 그대로 붙임", reply.getReqId() == 3 && reply.getType() == PacketType.LOGIN_FAIL);
        check("reqId 없으면 -1", new Packet(PacketType.ACK).getReqId() == -1);

        expectThrows("type 키는 put으로 못 넣음", IllegalArgumentException.class, () -> p.put("type", "SEND"));
        expectThrows("값에 줄바꿈 → 예외", IllegalArgumentException.class, () -> p.put("reason", "a\nb"));
        expectThrows("목록 항목에 쉼표 → 예외", IllegalArgumentException.class,
                () -> p.putList("receivers", List.of("lee", "pa,rk")));
        expectThrows("require: 없는 키 → 형식 오류", FormatException.class, () -> p.require("messageId"));
        expectThrows("requireInt: 정수 아님 → 형식 오류", FormatException.class,
                () -> new Packet(PacketType.PING).put("reqId", "abc").requireInt("reqId"));

        Packet list = new Packet(PacketType.SEND).putList("receivers", List.of("lee", "park"));
        check("putList / getList", list.getList("receivers").equals(List.of("lee", "park")));
        check("getList: 없는 키는 빈 목록", list.getList("cc").isEmpty());

        byte[] raw = {1, 2, 3};
        Packet d = new Packet(PacketType.FILE).setData(raw);
        raw[0] = 9;
        check("setData는 복사본 저장 (밖에서 고쳐도 안 바뀜)", d.getData()[0] == 1);

        check("PING·LOGIN만 로그인 전 허용",
                PacketType.PING.isAllowedBeforeLogin() && PacketType.LOGIN.isAllowedBeforeLogin()
                        && !PacketType.SEND.isAllowedBeforeLogin());
        check("응답 패킷은 요청이 아님", !PacketType.SEND_OK.isRequest() && PacketType.SEND.isRequest());
        check("모르는 이름 → null", PacketType.fromName("SNED") == null);
    }

    // ===================================================================
    // PacketReader / PacketWriter (설계서 2.1)
    // ===================================================================
    private static void packetReadWrite() throws Exception {
        section("PacketReader / PacketWriter - 패킷 경계");

        // 설계서·질문 답변에서 계산한 예시: 헤더 134바이트 + 데이터 64바이트 + 길이 8바이트 = 206바이트
        Packet send = new Packet(PacketType.SEND)
                .put("reqId", "7")
                .put("messageId", "3f2a9c1e-8b4d-4c1a-9e2f-1a2b3c4d5e6f")
                .put("receivers", "lee,park")
                .put("attachment.0", "a81b0c2d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
                .setText("안녕하세요 <font color=\"red\">중요 공지</font>입니다.");
        byte[] bytes = write(send);
        check("전체 206바이트 (4 + 헤더 134 + 4 + 데이터 64)", bytes.length == 206);
        check("앞 4바이트 = 헤더 길이 134 (00 00 00 86)",
                bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && (bytes[3] & 0xFF) == 0x86);
        check("본문은 40글자지만 64바이트 (한글 3바이트)",
                send.getText().length() == 40 && send.getDataLength() == 64);

        Packet back = new PacketReader(new ByteArrayInputStream(bytes)).read();
        check("다시 읽으면 종류·헤더·본문이 같음",
                back.getType() == PacketType.SEND && back.getHeader().equals(send.getHeader())
                        && back.getText().equals(send.getText()));

        // 두 패킷을 이어 보내도 경계를 정확히 나눈다 (본문에 줄바꿈과 '='가 있어도)
        Packet a = new Packet(PacketType.MSG).put("messageId", "A").setText("첫 줄\n\n둘째 줄 a=b");
        Packet b = new Packet(PacketType.PRESENCE).put("userId", "lee").put("online", "true");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketWriter w = new PacketWriter(out);
        w.write(a);
        w.write(b);
        PacketReader r = new PacketReader(new ByteArrayInputStream(out.toByteArray()));
        Packet ra = r.read();
        Packet rb = r.read();
        check("연속된 두 패킷을 정확히 나눠 읽음",
                ra.getText().equals(a.getText()) && rb.getType() == PacketType.PRESENCE && "true".equals(rb.get("online")));
        expectThrows("더 읽을 패킷이 없으면 EOFException (연결 종료)", EOFException.class, r::read);

        Packet unknown = new PacketReader(new ByteArrayInputStream(rawPacket("type=SNED\nreqId=5\n", new byte[0]))).read();
        check("모르는 종류는 예외 없이 읽고 getType()=null (서버가 ERROR로 응답)",
                unknown.getType() == null && "SNED".equals(unknown.getTypeName()) && unknown.getReqId() == 5);

        expectThrows("헤더 길이 0 → 형식 오류", FormatException.class,
                () -> readRaw(lengthOnly(0)));
        expectThrows("헤더 길이가 64KB 초과 → 형식 오류 (메모리 폭주 방지)", FormatException.class,
                () -> readRaw(lengthOnly(ProtocolInfo.MAX_HEADER_BYTES + 1)));
        expectThrows("데이터 길이 음수 → 형식 오류", FormatException.class,
                () -> readRaw(rawPacketWithDataLength("type=PING\n", -1)));
        expectThrows("데이터 길이 11MB 초과 → 형식 오류", FormatException.class,
                () -> readRaw(rawPacketWithDataLength("type=PING\n", ProtocolInfo.MAX_DATA_BYTES + 1)));
        expectThrows("첫 줄이 type= 아님 → 형식 오류", FormatException.class,
                () -> readRaw(rawPacket("reqId=1\ntype=PING\n", new byte[0])));
        expectThrows("패킷 중간에 끊김 → EOFException", EOFException.class,
                () -> readRaw(java.util.Arrays.copyOf(bytes, 100)));
        expectThrows("헤더에 같은 키 두 번 → 형식 오류", FormatException.class,
                () -> readRaw(rawPacket("type=PING\nreqId=1\nreqId=2\n", new byte[0])));
        expectThrows("데이터 11MB 초과 패킷은 쓰기 전에 거부", IllegalArgumentException.class,
                () -> write(new Packet(PacketType.FILE).setData(new byte[ProtocolInfo.MAX_DATA_BYTES + 1])));
    }

    // ===================================================================
    // HeaderBodyFile (설계서 3.2, 4.2)
    // ===================================================================
    private static void headerBodyFile() throws Exception {
        section("HeaderBodyFile - 메시지 파일 형식");

        Path dir = Files.createTempDirectory("coolmsg-test");
        Path file = dir.resolve("messages").resolve("m1.txt");

        HeaderBodyFile f = new HeaderBodyFile();
        f.put("messageId", "m1");
        f.put("sender", "kim");
        f.put("delivered.lee", "false"); // 서버 전용 키
        f.setBody("첫 줄\n\n빈 줄 다음 줄\nkey=value 처럼 보이는 줄");
        f.write(file);

        HeaderBodyFile g = HeaderBodyFile.read(file);
        check("저장 후 읽기: 머리글 순서·값 그대로 (폴더 자동 생성)", g.getHeader().equals(f.getHeader())
                && g.getHeader().keySet().iterator().next().equals("messageId"));
        check("본문의 빈 줄·'='도 그대로", g.getBody().equals(f.getBody()));

        g.put("delivered.lee", "true");
        g.put("readAt.lee", "2026-11-12T15:42:07");
        g.write(file);
        HeaderBodyFile h = HeaderBodyFile.read(file);
        check("값을 바꿔도 자리 유지, 새 키는 맨 뒤",
                List.copyOf(h.getHeader().keySet()).equals(List.of("messageId", "sender", "delivered.lee", "readAt.lee"))
                        && "true".equals(h.get("delivered.lee")));

        check("머리글 없이 본문만", HeaderBodyFile.parse("\n본문").getBody().equals("본문")
                && HeaderBodyFile.parse("\n본문").getHeader().isEmpty());
        check("본문이 빈 파일", HeaderBodyFile.parse("a=1\n\n").getBody().isEmpty());
        expectThrows("머리글 끝 빈 줄 없음 → 형식 오류", FormatException.class,
                () -> HeaderBodyFile.parse("a=1\nb=2\n"));

        Path broken = dir.resolve("broken.txt");
        Files.write(broken, new byte[] {'a', '=', (byte) 0xFF, '\n', '\n'});
        expectThrows("깨진 UTF-8 파일 → 형식 오류 (파일 이름 포함)", FormatException.class,
                () -> HeaderBodyFile.read(broken));
        expectThrows("없는 파일 → IOException", IOException.class,
                () -> HeaderBodyFile.read(dir.resolve("none.txt")));
    }

    // ===================================================================
    // AtomicFile
    // ===================================================================
    private static void atomicFile() throws Exception {
        section("AtomicFile - 반쯤 쓴 파일 방지");

        Path dir = Files.createTempDirectory("coolmsg-atomic");
        Path file = dir.resolve("a").resolve("b").resolve("users.txt");
        AtomicFile.writeText(file, "옛 내용");
        AtomicFile.writeText(file, "새 내용");
        check("덮어쓰기 후 새 내용 전체 (폴더 자동 생성)",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8).equals("새 내용"));
        try (Stream<Path> files = Files.list(file.getParent())) {
            check("임시 파일(.tmp)이 남지 않음", files.noneMatch(x -> x.toString().endsWith(".tmp")));
        }
    }

    // ===================================================================
    // TimeFormat (설계서 5장 U2)
    // ===================================================================
    private static void timeFormat() throws Exception {
        section("TimeFormat - 시각 형식 U2");

        LocalDateTime t = LocalDateTime.of(2026, 11, 12, 15, 30, 12, 987_000_000);
        check("초 아래는 버림", TimeFormat.format(t).equals("2026-11-12T15:30:12"));
        check("문자열 → 시각", TimeFormat.parse("2026-11-12T15:30:12").equals(LocalDateTime.of(2026, 11, 12, 15, 30, 12)));
        check("now()는 초 단위", TimeFormat.now().getNano() == 0);
        expectThrows("없는 날짜(2월 30일) → 형식 오류", FormatException.class, () -> TimeFormat.parse("2026-02-30T10:00:00"));
        expectThrows("형식 다름(공백 구분) → 형식 오류", FormatException.class, () -> TimeFormat.parse("2026-11-12 15:30:12"));
    }

    // ===================================================================
    // model: DeliveryState, AttachmentInfo, MessageData
    // ===================================================================
    private static void model() throws Exception {
        section("model - 상태, 첨부 정보, 메시지");

        check("상태는 앞으로만: READ가 DELIVERED보다 앞섬", DeliveryState.READ.isAfter(DeliveryState.DELIVERED));
        check("같은 상태는 앞서지 않음 (중복 알림 무시)", !DeliveryState.DELIVERED.isAfter(DeliveryState.DELIVERED));
        check("뒤 상태가 늦게 와도 무시", !DeliveryState.DELIVERED.isAfter(DeliveryState.READ));
        check("기록이 없으면(null) 어떤 상태든 반영", DeliveryState.SENT.isAfter(null));

        AttachmentInfo a = AttachmentInfo.parse("a81b|204800|2026-12-12T15:30:12|회의|자료,최종.pdf");
        check("첨부: 파일 이름 안의 '|'·','는 이름으로 유지", a.getFileName().equals("회의|자료,최종.pdf") && a.getSize() == 204800);
        check("첨부: 다시 헤더 값으로", a.toHeaderValue().equals("a81b|204800|2026-12-12T15:30:12|회의|자료,최종.pdf"));
        expectThrows("첨부: 칸 부족 → 형식 오류", FormatException.class, () -> AttachmentInfo.parse("a81b|204800"));
        expectThrows("첨부: 크기가 숫자 아님 → 형식 오류", FormatException.class,
                () -> AttachmentInfo.parse("a81b|큼|2026-12-12T15:30:12|a.pdf"));

        // SEND처럼 sender·sentAt 없이 만든 메시지
        MessageData m = new MessageData("3f2a", List.of("lee", "park"), List.of(), "본문\n둘째 줄");
        Map<String, String> h = m.toHeader();
        check("SEND용 헤더: sender·sentAt·cc 줄 없음",
                !h.containsKey("sender") && !h.containsKey("sentAt") && !h.containsKey("cc")
                        && "lee,park".equals(h.get("receivers")));

        // 서버가 채운 뒤 (MSG, 메시지 파일)
        m.setSender("kim");
        m.setSentAt(LocalDateTime.of(2026, 11, 12, 15, 30, 12));
        m.setReplyTo("0a1b");
        MessageData full = new MessageData("3f2a", List.of("lee"), List.of("choi"), "본문");
        full.setSender("kim");
        full.setSentAt(LocalDateTime.of(2026, 11, 12, 15, 30, 12));

        // 파일 형식에 서버 전용 키를 덧붙여도 MessageData로 읽을 수 있다
        HeaderBodyFile file = new HeaderBodyFile(full.toHeader(), full.getBody());
        file.put("delivered.lee", "false");
        HeaderBodyFile reread = HeaderBodyFile.parse(file.toText());
        MessageData back = MessageData.fromHeader(reread.getHeader(), reread.getBody());
        check("파일 → 메시지: 모든 값 복원, 서버 전용 키는 무시",
                back.getMessageId().equals("3f2a") && back.getSender().equals("kim")
                        && back.getReceivers().equals(List.of("lee")) && back.getCc().equals(List.of("choi"))
                        && back.getSentAt().equals(full.getSentAt()) && back.getBody().equals("본문")
                        && back.getAttachments().isEmpty());
        check("replyTo 왕복", MessageData.fromHeader(m.toHeader(), "x").getReplyTo().equals("0a1b"));

        Map<String, String> noId = new LinkedHashMap<>();
        noId.put("receivers", "lee");
        expectThrows("messageId 없음 → 형식 오류", FormatException.class, () -> MessageData.fromHeader(noId, ""));
        Map<String, String> badTime = new LinkedHashMap<>(full.toHeader());
        badTime.put("sentAt", "어제");
        expectThrows("sentAt 형식 틀림 → 형식 오류", FormatException.class, () -> MessageData.fromHeader(badTime, ""));
        expectThrows("받는 사람 목록은 읽기 전용", UnsupportedOperationException.class,
                () -> m.getReceivers().add("hacker"));
    }

    // ===================================================================
    // 도우미
    // ===================================================================
    private static byte[] write(Packet p) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PacketWriter(out).write(p);
        return out.toByteArray();
    }

    private static Packet readRaw(byte[] bytes) throws IOException {
        return new PacketReader(new ByteArrayInputStream(bytes)).read();
    }

    /** 규칙을 일부러 어긴 패킷을 만들기 위해 PacketWriter 없이 직접 바이트를 쓴다. */
    private static byte[] rawPacket(String header, byte[] data) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bout);
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        out.writeInt(h.length);
        out.write(h);
        out.writeInt(data.length);
        out.write(data);
        return bout.toByteArray();
    }

    private static byte[] rawPacketWithDataLength(String header, int dataLength) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bout);
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        out.writeInt(h.length);
        out.write(h);
        out.writeInt(dataLength);
        return bout.toByteArray();
    }

    private static byte[] lengthOnly(int length) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        new DataOutputStream(bout).writeInt(length);
        return bout.toByteArray();
    }

    // ===================================================================
    // HtmlText (설계서 5장 U37)
    // ===================================================================

    private static void htmlText() {
        section("HtmlText - 본문 HTML 변환 U37");
        check("특수 문자 바꾸기", HtmlText.fromPlain("a<b>&\"c").equals("a&lt;b&gt;&amp;&quot;c"));
        check("줄바꿈 → <br> (CRLF도 한 번만)", HtmlText.fromPlain("1\r\n2\n3").equals("1<br>\n2<br>\n3"));
        String plain = "<script> & 줄1\n줄2";
        check("일반 텍스트 → HTML → 글자: 원래대로", HtmlText.toPlain(HtmlText.fromPlain(plain)).equals(plain));
        check("&amp;lt; 는 글자 '&lt;' 로 (두 번 풀지 않음)", HtmlText.toPlain("&amp;lt;").equals("&lt;"));
        check("태그 제거", HtmlText.toPlain("<b>굵게</b> <font color=\"red\">빨강</font>").equals("굵게 빨강"));
        check("빈 본문: 태그·공백·&nbsp;뿐", HtmlText.isBlank("<p> </p><br>&nbsp;\n") && HtmlText.isBlank(""));
        check("글자가 있으면 빈 본문 아님", !HtmlText.isBlank("<p>.</p>"));
        check("미리보기: 줄바꿈은 공백, 길면 …",
                HtmlText.preview(HtmlText.fromPlain("가나다\n라마바사"), 5).equals("가나다 라…")
                        && HtmlText.preview("짧음", 30).equals("짧음"));
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("[" + name + "]");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static void expectThrows(String name, Class<? extends Throwable> expected, Action action) {
        try {
            action.run();
            check(name + "  (예외가 나지 않음)", false);
        } catch (Throwable t) {
            boolean ok = expected.isInstance(t);
            check(ok ? name : name + "  (다른 예외: " + t + ")", ok);
        }
    }
}
