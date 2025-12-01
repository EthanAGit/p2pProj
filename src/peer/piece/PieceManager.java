package peer.piece;

import java.util.Arrays;

/** Tracks my bitfield and picks next needed piece given a neighbor's bitfield. */
public class PieceManager {
    private final int numPieces;
    private final byte[] myBits; // packed bitfield, MSB-first per byte

    public PieceManager(int numPieces, boolean startWithAll) {
        this.numPieces = numPieces;
        int bytes = (numPieces + 7) / 8;
        this.myBits = new byte[bytes];
        if (startWithAll) Arrays.fill(myBits, (byte)0xFF);
        trimExtraBits();
    }

    /** Call when you write a piece. */
    public synchronized void markHave(int index) {
        int b = index >>> 3, off = 7 - (index & 7);
        myBits[b] = (byte)(myBits[b] | (1 << off));
    }

    public synchronized boolean have(int index) {
        int b = index >>> 3, off = 7 - (index & 7);
        return (myBits[b] & (1 << off)) != 0;
    }

    public synchronized byte[] myBitfield() { return Arrays.copyOf(myBits, myBits.length); }

    /** First index I don't have that the neighbor does; -1 if none. */
    public synchronized int nextNeededFrom(byte[] neighborBits) {
        for (int i = 0; i < numPieces; i++) {
            if (!have(i) && bit(neighborBits, i)) return i;
        }
        return -1;
    }

    private static boolean bit(byte[] bits, int index) {
        int b = index >>> 3, off = 7 - (index & 7);
        if (b < 0 || b >= bits.length) return false;
        return (bits[b] & (1 << off)) != 0;
    }
     public synchronized boolean isComplete() {
        for (int i = 0; i < numPieces; i++) {
            if (!have(i)) return false;
        }
        return true;
    }
    public synchronized boolean bitfieldIsComplete(byte[] bits) {
    if (bits == null) return false;
    for (int i = 0; i < numPieces; i++) {
        if (!bit(bits, i)) {
            return false;
        }
    }
    return true;
}
    private void trimExtraBits() {
        int extra = (myBits.length * 8) - numPieces;
        if (extra > 0) {
            int mask = 0xFF & (0xFF << extra);
            myBits[myBits.length - 1] &= mask;
        }
    }
}
