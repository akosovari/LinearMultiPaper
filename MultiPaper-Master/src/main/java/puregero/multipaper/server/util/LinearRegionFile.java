package puregero.multipaper.server.util;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.zip.*;
import java.util.ArrayList;

import com.github.luben.zstd.ZstdOutputStream;
import com.github.luben.zstd.ZstdInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.xxhash.XXHashFactory;

public class LinearRegionFile {
    public File regionFile;

    private final byte[][] buffer = new byte[32*32][];
    private final int[] bufferUncompressedSize = new int[32*32];
    private boolean requiresSaving = false;
    private long lastUpdate = 0;
    private final long SAVE_FORCE_INTERVAL = 10 * 1000000000;
    final byte COMPRESSION_LEVEL = 1;

    private boolean entities = false; // TODO: Remove

    public LinearRegionFile(File path) {
        Path file = path.toPath();
        this.regionFile = new File(file.toString());
        this.lastUpdate = System.nanoTime();

        entities = file.toString().contains("entities");

        LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();
        LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();

        for (int i = 0 ; i < 32 * 32 ; i++)
            this.bufferUncompressedSize[i] = 0;

        try {
            if (this.regionFile.canRead()) {
                long start = System.nanoTime();

                long fileLength = path.length();
                FileInputStream fileStream = new FileInputStream(regionFile);
                DataInputStream rawDataStream = new DataInputStream(fileStream);

                long SUPERBLOCK = -4323716122432332390L;
                byte VERSION = 1;
                int HEADER_SIZE = 32;
                int FOOTER_SIZE = 8;

                long superBlock = rawDataStream.readLong();

                if (superBlock != SUPERBLOCK) {
                    System.out.println(file.toString());
                    System.out.println("SUPERBLOCK INVALID!");
                    return;
                }

                byte version = rawDataStream.readByte();

                if (version != VERSION) {
                    System.out.println(file.toString());
                    System.out.println("VERSION INVALID!");
                    return;
                }

                long newestTimestamp = rawDataStream.readLong();
                byte compressionLevel = rawDataStream.readByte();
                short chunkCount = rawDataStream.readShort();
                int dataCount = rawDataStream.readInt();

                if (fileLength != HEADER_SIZE + dataCount + FOOTER_SIZE) {
                    System.out.println(file.toString());
                    System.out.println("FILE LENGTH INVALID! " + String.valueOf(fileLength) + " " + String.valueOf(HEADER_SIZE + dataCount + FOOTER_SIZE));
                    return;
                }

                long dataHash = rawDataStream.readLong();
                byte[] rawCompressed = new byte[dataCount];

                rawDataStream.readFully(rawCompressed, 0, dataCount);

                superBlock = rawDataStream.readLong();

                if (superBlock != SUPERBLOCK) {
                    System.out.println(file.toString());
                    System.out.println("FOOTER SUPERBLOCK INVALID!");
                    return;
                }

                DataInputStream dataStream = new DataInputStream(new ZstdInputStream​(new ByteArrayInputStream(rawCompressed)));

                int completeDataCount = 0;
                int total = 4096 * 2;
                int starts[] = new int[32 * 32];
                int timestamps[] = new int[32 * 32];
                for(int i = 0 ; i < 32 * 32 ; i++) {
                    starts[i] = dataStream.readInt();
                    timestamps[i] = dataStream.readInt();
                }

                for(int i = 0 ; i < 32 * 32 ; i++) {
                    if(starts[i] > 0) {
                        int size = starts[i];
                        completeDataCount += size;
                        byte b[] = new byte[size];
                        dataStream.readFully(b, 0, size);

                        int maxCompressedLength = compressor.maxCompressedLength(size);
                        byte[] compressed = new byte[maxCompressedLength];
                        int compressedLength = compressor.compress(b, 0, size, compressed, 0, maxCompressedLength);
                        b = new byte[compressedLength];
                        for(int j = 0 ; j < compressedLength ; j++)
                            b[j] = compressed[j];

                        this.buffer[i] = b;
                        this.bufferUncompressedSize[i] = size;
                    }
                }
            }
        } catch (IOException ex) {
            System.out.println("Region file corrupted! " + this.regionFile);
            // TODO: Move to temp file and regenerate
        }
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + (z & 31) * 32;
    }

    public synchronized byte[] getDeflatedBytes(int x, int z) {
        if(this.bufferUncompressedSize[getChunkIndex(x, z)] != 0) {
            LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();
            try {
                byte[] content = new byte[bufferUncompressedSize[getChunkIndex(x, z)]];
                decompressor.decompress(this.buffer[getChunkIndex(x, z)], 0, content, 0, bufferUncompressedSize[getChunkIndex(x, z)]);
                return toByteArray(new DeflaterInputStream(new ByteArrayInputStream(content), new Deflater(Deflater.BEST_SPEED)));
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private byte[] toByteArray(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tempBuffer = new byte[4096];

        int length;
        while ((length = in.read(tempBuffer)) >= 0) {
            out.write(tempBuffer, 0, length);
        }

        return out.toByteArray();
    }

    public synchronized void putDeflatedBytes(int x, int z, byte[] b) {
        LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();
        try {
            b = toByteArray(new InflaterInputStream(new ByteArrayInputStream(b)));
            int uncompressedSize = b.length;

            int maxCompressedLength = compressor.maxCompressedLength(b.length);
            byte[] compressed = new byte[maxCompressedLength];
            int compressedLength = compressor.compress(b, 0, b.length, compressed, 0, maxCompressedLength);
            b = new byte[compressedLength];
            for(int j = 0 ; j < compressedLength ; j++)
                b[j] = compressed[j];

            this.buffer[getChunkIndex(x, z)] = b;
            this.bufferUncompressedSize[getChunkIndex(x, z)] = uncompressedSize;

            this.lastUpdate = System.nanoTime();
        } catch (IOException e) {
            if (!entities)
                System.out.println("PutDeflatedBytes exception " + entities + " " + e.toString() + " " + this.regionFile);
        }
        this.requiresSaving = true;
    }

    public synchronized void close() throws IOException {
        flush();
    }

    public synchronized void flush() throws IOException {
        if (!this.requiresSaving) return;

        long start = System.nanoTime();

        long SUPERBLOCK = -4323716122432332390L;
        byte VERSION = 1;
        long timestamp = System.currentTimeMillis() / 1000L;
        short chunkCount = 0;

        File tempFile = new File(regionFile.toString() + ".tmp");
        FileOutputStream fileStream = new FileOutputStream(tempFile);

        ByteArrayOutputStream zstdByteArray = new ByteArrayOutputStream();
        ZstdOutputStream zstdStream = new ZstdOutputStream​(zstdByteArray, COMPRESSION_LEVEL);
        zstdStream.setChecksum​(true);
        DataOutputStream zstdDataStream = new DataOutputStream(zstdStream);
        DataOutputStream dataStream = new DataOutputStream(fileStream);

        dataStream.writeLong(SUPERBLOCK);
        dataStream.writeByte(VERSION);
        dataStream.writeLong(timestamp);
        dataStream.writeByte(COMPRESSION_LEVEL);

        int region_total = 0;
        int region_raw = 0;

        LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();

        ArrayList<byte[]> byteBuffers = new ArrayList<byte[]>();
        for(int i = 0 ; i < 32 * 32 ; i++) {
            if(this.bufferUncompressedSize[i] != 0) {
                chunkCount += 1;
                long compStart = System.nanoTime();
                byte[] content = new byte[bufferUncompressedSize[i]];
                decompressor.decompress(buffer[i], 0, content, 0, bufferUncompressedSize[i]);

                region_total += buffer[i].length;
                region_raw += content.length;

                byteBuffers.add(content);
            } else byteBuffers.add(null);
        }
        for(int i = 0 ; i < 32 * 32 ; i++) {
            zstdDataStream.writeInt(this.bufferUncompressedSize[i]);
            zstdDataStream.writeInt(0);
        }
        for(int i = 0 ; i < 32 * 32 ; i++) {
            if(byteBuffers.get(i) != null)
                zstdDataStream.write(byteBuffers.get(i), 0, byteBuffers.get(i).length);
        }
        zstdDataStream.close();

        dataStream.writeShort(chunkCount);

        byte[] compressed = zstdByteArray.toByteArray();

        dataStream.writeInt(compressed.length);
        dataStream.writeLong(XXHashFactory.fastestInstance().hash64().hash(compressed, 0, compressed.length, 0)); // TODO: Hash the contents, not the whole thing

        dataStream.write(compressed, 0, compressed.length);
        dataStream.writeLong(SUPERBLOCK);

        dataStream.close();

        fileStream.close();
        Files.move(tempFile.toPath(), regionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        this.requiresSaving = false;

        if(region_raw != 0) {
            System.out.println("Region file flush " + String.valueOf(System.nanoTime() - start) + " compression " + String.valueOf(100 * region_total / region_raw) + "%");
            System.out.println("MEMORY " + String.valueOf(Runtime.getRuntime().totalMemory()) + "    " + String.valueOf(Runtime.getRuntime().freeMemory()));
        }
    }

    public synchronized void flushOnSchedule() {
        try {
            if(this.requiresSaving && System.nanoTime() > lastUpdate + SAVE_FORCE_INTERVAL)
                flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
