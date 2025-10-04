package peer;
import peer.net.Client;
import peer.net.Server;
public class peerProcess {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java peer.peerProcess <peerId>");
            System.exit(1);
        }
        String peerId = args[0];
        System.out.println("Peer " + peerId + " starting...");
        System.out.println("Working dir is where Common.cfg / PeerInfo.cfg live.");
        // TODO: parse cfgs, open sockets, etc.
        System.out.println("Peer " + peerId + " is idle. Press Enter to quit.");
        new java.util.Scanner(System.in).nextLine();
    }
}