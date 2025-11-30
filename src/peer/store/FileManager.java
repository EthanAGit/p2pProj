package peer.store;

import peer.io.Config;
import java.io.*;
import java.nio.file.*;

/** Maps piece index <-> file offsets and does read/write of pieces. */
public class FileManager {
    private final Path dir;           // peer_<id>/
    private final Path filePath;      // peer_<id>/<FileName>
    private final long fileSize;
    private final int pieceSize;
    private final int numPieces;

    public FileManager(String peerId, Config cfg) throws IOException {
        this.fileSize = cfg.fileSize;
        this.pieceSize = cfg.pieceSize;
        this.numPieces = cfg.numPieces;
        this.dir = Paths.get(peerId);
        Files.createDirectories(dir);
        this.filePath = dir.resolve(cfg.fileName);

        // If file missing, create the right-sized sparse file so RandomAccessFile works.
        if (!Files.exists(filePath)) {
            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
                raf.setLength(fileSize);
            }
        }
    }

    public int pieceLength(int index) {
        long start = (long) index * pieceSize;
        long remaining = fileSize - start;
        return (int) Math.min(pieceSize, Math.max(0, remaining));
    }

    public byte[] readPiece(int index) throws IOException {
        int len = pieceLength(index);
        byte[] buf = new byte[len];
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek((long) index * pieceSize);
            raf.readFully(buf);
        }
        return buf;
    }

    public void writePiece(int index, byte[] data) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.seek((long) index * pieceSize);
            raf.write(data);
        }
    }
}
