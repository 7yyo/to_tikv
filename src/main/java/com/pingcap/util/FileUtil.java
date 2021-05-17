package com.pingcap.util;

import com.pingcap.importer.IndexInfoS2T_bak;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FileUtil {

    private static final Logger logger = Logger.getLogger(FileUtil.class);

    private static final List<File> list = new ArrayList<>();
    private static final Properties properties = PropertiesUtil.getProperties();

    public static List<File> loadDirectory(File fileList) {
        File[] files = fileList.listFiles();
        if (files == null) {
            logger.error("Files is not exists!");
            return null;
        }
        List<File> insideFiles = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory()) {
                insideFiles.add(file);
            } else {
                if (!file.getAbsolutePath().contains("DS")) {
                    list.add(file);
                }
            }
        }
        for (File file : insideFiles) {
            loadDirectory(file);
        }
        return list;
    }

    public static int getFileLines(File file) {
        FileReader in = null;
        int lines = 0;
        try {
            in = new FileReader(file);
            LineNumberReader reader = new LineNumberReader(in);
            reader.skip(Long.MAX_VALUE);
            lines = reader.getLineNumber() + 1;
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lines;
    }

    public static ConcurrentHashMap<String, Long> getTtlTypeMap(List<String> list) {
        ConcurrentHashMap<String, Long> ttlTypeCountMap = new ConcurrentHashMap<>();
        for (String ttlType : list) {
            ttlTypeCountMap.put(ttlType, 0L);
        }
        return ttlTypeCountMap;
    }

    public static List<File> showFileList(String filePath) {
        logger.info(String.format("Welcome to To_TiKV."));
        logger.info(String.format("Properties->%s", properties));
        List<File> fileList = FileUtil.loadDirectory(new File(filePath));
        assert fileList != null;
        if (fileList.isEmpty()) {
            logger.error(String.format("%s has no file.", filePath));
        } else {
            for (int i = 0; i < fileList.size(); i++) {
                logger.info(String.format("No.%s:'%s'", i, fileList.get(i).getAbsolutePath()));
            }
        }
        logger.info(String.format("Need to import the above files, total [%s]", fileList.size()));
        return fileList;
    }

}
