package peer.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Connection implements Runnable {
    private final Socket sock;
    private final String myId;

    public Connection(Socket sock, String myId) {
        this.sock = sock;
        this.myId = myId;
    }

    public void start() {
        new Thread(this, "Conn-" + myId + "->" + sock.getRemoteSocketAddress()).start();
    }
    private static final byte[] HS_HEADER =
        "P2PFILESHARINGPROJ".getBytes(StandardCharsets.US_ASCII); // 18B
    private static final int HS_TOTAL = 32;

    private static void sendHandshake(OutputStream out, int myPeerId) throws IOException {
        DataOutputStream dout = new DataOutputStream(out);
        byte[] buf = new byte[HS_TOTAL];                      // makes 32 bytes, all zeroed
        System.arraycopy(HS_HEADER, 0, buf, 0, HS_HEADER.length); // put 18B header at positions 0..17
        // bytes 18..27 stay zero (that’s the “10 zero bits/bytes” in the spec)
        // write peerId in big-endian at positions 28..31:
        buf[28] = (byte)((myPeerId >>> 24) & 0xFF);
        buf[29] = (byte)((myPeerId >>> 16) & 0xFF);
        buf[30] = (byte)((myPeerId >>>  8) & 0xFF);
        buf[31] = (byte)((myPeerId       ) & 0xFF);
        dout.write(buf);                                      // push all 32 bytes to the socket
        dout.flush();                                         // flush Java’s buffer to the OS
    }
    
    private static int recvHandshake(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        byte[] buf = new byte[HS_TOTAL];                      // a place to store exactly 32 bytes
        din.readFully(buf);                                   // BLOCKS until all 32 bytes arrive
        // validate header matches "P2PFILESHARINGPROJ"
        for (int i = 0; i < HS_HEADER.length; i++) {
            if (buf[i] != HS_HEADER[i]) throw new IOException("Bad handshake header");
        }
        // reconstruct 4-byte big-endian peerId from positions 28..31:
        return ((buf[28] & 0xFF) << 24) |
            ((buf[29] & 0xFF) << 16) |
            ((buf[30] & 0xFF) <<  8) |
                (buf[31] & 0xFF);
    }
    @Override public void run() {
        try (var in = sock.getInputStream(); var out = sock.getOutputStream()) {
            // Socket options: lower latency and avoid infinite read hangs
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(15000);

            // Wrap once so send/recv helpers can use them
            din = new DataInputStream(in);
            dout = new DataOutputStream(out);

            // ---- Handshake (both sides do "send then read" to avoid deadlock) ----
            int my = Integer.parseInt(myId);
            sendHandshake(out, my);
            int remote = recvHandshake(in);
            System.out.println("[" + myId + "] handshake OK with peer " + remote +
                               " via " + sock.getRemoteSocketAddress());

           
            sendInterested();
            
            // ---- Enter the message loop: parse frames and call empty handlers ----
            readLoop();  // returns only on EOF/IOException
        } catch (Exception e) {
            System.out.println("[" + myId + "] connection closed: " + e.getMessage());
        }
    }
    private DataInputStream din;
    private DataOutputStream dout;
    private static final byte MSG_CHOKE           = 0;
    private static final byte MSG_UNCHOKE         = 1;
    private static final byte MSG_INTERESTED      = 2;
    private static final byte MSG_NOT_INTERESTED  = 3;
    private static final byte MSG_HAVE            = 4;
    private static final byte MSG_BITFIELD        = 5;
    private static final byte MSG_REQUEST         = 6;
    private static final byte MSG_PIECE           = 7;

    private static String typeName(int t) {
        return switch (t) {
            case 0 -> "choke";
            case 1 -> "unchoke";
            case 2 -> "interested";
            case 3 -> "not_interested";
            case 4 -> "have";
            case 5 -> "bitfield";
            case 6 -> "request";
            case 7 -> "piece";
            default -> "unknown(" + t + ")";
        };
    }
    private void sendFrame(byte type, byte[] payload) throws IOException {
        int payloadLen = (payload == null) ? 0 : payload.length;
        int len = 1 + payloadLen;       // type + payload
        dout.writeInt(len);             // big-endian 4-byte length
        dout.writeByte(type);           // 1-byte message type
        if (payloadLen > 0) dout.write(payload);
        System.out.printf("[%s] → %s (%d bytes payload)%n",
        myId, typeName(type), (payload == null ? 0 : payload.length));
        dout.flush();
    }
    public void sendChoke()            throws IOException { sendFrame(MSG_CHOKE, null); }
    public void sendUnchoke()          throws IOException { sendFrame(MSG_UNCHOKE, null); }
    public void sendInterested()       throws IOException { sendFrame(MSG_INTERESTED, null); }
    public void sendNotInterested()    throws IOException { sendFrame(MSG_NOT_INTERESTED, null); }

    /** have(index) — payload is a 4-byte piece index (big-endian) */
    public void sendHave(int pieceIndex) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(pieceIndex);
        sendFrame(MSG_HAVE, bb.array());
    }

    /** bitfield(raw bytes) — payload is your bitfield as a packed byte array */
    public void sendBitfield(byte[] bitfield) throws IOException {
        sendFrame(MSG_BITFIELD, bitfield);
    }

    /** request(index) — payload is a 4-byte piece index (big-endian) */
    public void sendRequest(int pieceIndex) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(pieceIndex);
        sendFrame(MSG_REQUEST, bb.array());
    }

    /** piece(index + data) — payload is 4-byte index followed by raw piece bytes */
    public void sendPiece(int pieceIndex, byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4 + data.length);
        bb.putInt(pieceIndex);
        bb.put(data);
        sendFrame(MSG_PIECE, bb.array());
    }
    private void readLoop() throws IOException {
        while (true) {
            int len  = din.readInt();              // number of bytes after this field
            int type = din.readUnsignedByte();     // 0..255
            int payloadLen = len - 1;              // remaining after the 1-byte type
            byte[] payload = (payloadLen > 0) ? new byte[payloadLen] : null;
            System.out.printf("[%s] ← %s (%d bytes payload)%n",
            myId, typeName(type), payloadLen);
            if (payloadLen > 0) din.readFully(payload);

            switch (type) {
                case MSG_CHOKE           -> onChoke();
                case MSG_UNCHOKE         -> onUnchoke();
                case MSG_INTERESTED      -> onInterested();
                case MSG_NOT_INTERESTED  -> onNotInterested();
                case MSG_HAVE -> {
                    int idx = ByteBuffer.wrap(payload).getInt(); // big-endian by default
                    onHave(idx);
                }
                case MSG_BITFIELD        -> onBitfield(payload);
                case MSG_REQUEST -> {
                    int idx = ByteBuffer.wrap(payload).getInt();
                    onRequest(idx);
                }
                case MSG_PIECE -> {
                    ByteBuffer bb = ByteBuffer.wrap(payload);
                    int idx = bb.getInt();
                    byte[] data = new byte[bb.remaining()];
                    bb.get(data);
                    onPiece(idx, data);
                }
                default -> throw new IOException("Unknown msg type: " + type);
            }
        }
    }

    // ------------------------ Stub handlers (no behavior yet) ------------------------
    private void onChoke() {}
    private void onUnchoke() {}
    private void onInterested() {}
    private void onNotInterested() {}
    private void onHave(int pieceIndex) {}
    private void onBitfield(byte[] bitfield) {}
    private void onRequest(int pieceIndex) {}
    private void onPiece(int pieceIndex, byte[] data) {}
}
