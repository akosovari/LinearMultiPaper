package puregero.multipaper.server.util;

/*
 ** 2011 January 5
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */

/*
 * 2011 February 16
 *
 * This source code is based on the work of Scaevolus (see notice above).
 * It has been slightly modified by Mojang AB to limit the maximum cache
 * size (relevant to extremely big worlds on Linux systems with limited
 * number of file handles). The region files are postfixed with ".mcr"
 * (Minecraft region file) instead of ".data" to differentiate from the
 * original McRegion files.
 *
 */

// A simple cache and wrapper for efficiently multiple RegionFiles simultaneously.

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RegionFileCache extends Thread {

    private static final int MAX_CACHE_SIZE = Integer.getInteger("max.regionfile.cache.size", 256);
    private static RegionFileCache single_instance = null;

    private final LinkedHashMap<File, Reference<LinearRegionFile>> cache = new LinkedHashMap<>(16, 0.75f, true);
    private Thread thread;

    private RegionFileCache() {
        if (thread == null) {
            thread = new Thread(this);
            thread.start();
        }
    }

    synchronized public static RegionFileCache i() {
        if (single_instance == null)
            single_instance = new RegionFileCache();
        return single_instance;
    }

    public synchronized List<LinearRegionFile> getRegionFiles() {
        ArrayList<LinearRegionFile> array = new ArrayList();
        for (Map.Entry<File, Reference<LinearRegionFile>> entry: cache.entrySet()) {
            LinearRegionFile region = entry.getValue().get();
            array.add(region);
        }
        return array;
    }

    public void run() {
        System.out.println("Starting storage thread");
        while (true) {
            ArrayList<LinearRegionFile> array;
            synchronized(this) {
                array = new ArrayList(cache.size());
                for (Map.Entry<File, Reference<LinearRegionFile>> entry: cache.entrySet()) {
                    LinearRegionFile region = entry.getValue().get();
                    array.add(region);
                }
            }
            for (LinearRegionFile region: array) {
                synchronized(this) {
                    region.flushOnSchedule();
                }
                try{Thread.sleep(10 * 1000 / array.size());} catch(InterruptedException ex) {}
            }
            try {
                Thread.sleep(10 * 1000);
                System.out.println("Cache size " + cache.size());
            } catch(InterruptedException ex) {}
        }
    }

    public synchronized boolean isRegionFileOpen(File regionDir, int chunkX, int chunkZ) {
        File file = new File(regionDir, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".linear");

        file = canonical(file);

        Reference<LinearRegionFile> ref = cache.get(file);

        if (ref != null && ref.get() != null) {
            return true;
        }

        return false;
    }

    private File canonical(File file) {
        // Not using getCanonicalPath to stop it from following symlinks
        // With symlinks not followed we can store old chunks on HDD and automatically move them to SSD on access
        return new File(file.getAbsolutePath());
    }
    
    private File getFileForRegionFile(File regionDir, int chunkX, int chunkZ) {
        return new File(regionDir, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".linear");
    }

    public synchronized LinearRegionFile getRegionFileIfExists(File regionDir, int chunkX, int chunkZ) {
        if (getFileForRegionFile(regionDir, chunkX, chunkZ).isFile()) {
            return getRegionFile(regionDir, chunkX, chunkZ);
        } else {
            return null;
        }
    }

    public synchronized LinearRegionFile getRegionFile(File regionDir, int chunkX, int chunkZ) {
        File file = getFileForRegionFile(regionDir, chunkX, chunkZ);

        file = canonical(file);

        Reference<LinearRegionFile> ref = cache.get(file);

        if (ref != null && ref.get() != null) {
            return ref.get();
        }

        if (!regionDir.exists()) {
            regionDir.mkdirs();
        }

        if (cache.size() >= MAX_CACHE_SIZE) {
            clearOne();
        }

        LinearRegionFile reg = new LinearRegionFile(file);
        cache.put(file, new SoftReference<>(reg));
        return reg;
    }

    private synchronized void clearOne() {
        Map.Entry<File, Reference<LinearRegionFile>> clearEntry = cache.entrySet().iterator().next();
        cache.remove(clearEntry.getKey());
        try {
            LinearRegionFile removeFile = clearEntry.getValue().get();
            if (removeFile != null) {
                removeFile.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized byte[] getChunkDeflatedData(File basePath, int chunkX, int chunkZ) {
        try {
            LinearRegionFile r = getRegionFileIfExists(basePath, chunkX, chunkZ);
            if (r != null) {
                return r.getDeflatedBytes(chunkX, chunkZ);
            } else {
                return null;
            }
        } catch (Throwable throwable) {
            System.err.println("Error when trying to read chunk " + chunkX + "," + chunkZ + " in " + basePath);
            throw throwable;
        }
    }

    public synchronized void putChunkDeflatedData(File basePath, int chunkX, int chunkZ, byte[] data) {
        try {
            LinearRegionFile r = getRegionFile(basePath, chunkX, chunkZ);
            r.putDeflatedBytes(chunkX & 31, chunkZ & 31, data);
        } catch (Throwable throwable) {
            System.err.println("Error when trying to write chunk " + chunkX + "," + chunkZ + " in " + basePath);
            throw throwable;
        }
    }

    public synchronized void shutdown() {
        while(!cache.isEmpty()) clearOne();
    }
}
