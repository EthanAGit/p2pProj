package peer.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
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
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(15000);
    
            int my = Integer.parseInt(myId);
    
            // 1) SEND then 2) READ the 32-byte handshake
            sendHandshake(out, my);
            int remote = recvHandshake(in);
    
            System.out.println("[" + myId + "] handshake OK with peer " + remote +
                               " via " + sock.getRemoteSocketAddress());
    
            // keep the connection alive for now
            while (!sock.isClosed()) Thread.sleep(1000);
        } catch (Exception e) {
            System.out.println("[" + myId + "] connection closed: " + e.getMessage());
        }
    }}
