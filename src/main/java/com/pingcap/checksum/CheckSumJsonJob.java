package com.pingcap.checksum;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import static com.pingcap.enums.Model.*;

import com.pingcap.enums.Model;
import com.pingcap.pojo.IndexInfo;
import com.pingcap.pojo.TempIndexInfo;
import com.pingcap.timer.CheckSumTimer;
import com.pingcap.util.CountUtil;
import com.pingcap.util.FileUtil;
import io.prometheus.client.Histogram;
import org.apache.commons.io.LineIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.TiSession;
import org.tikv.kvproto.Kvrpcpb;
import org.tikv.raw.RawKVClient;
import org.tikv.shade.com.google.protobuf.ByteString;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CheckSumJsonJob implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Model.LOG);
    private static final Logger checkSumLog = LoggerFactory.getLogger(CHECK_SUM_LOG);

    static final Histogram CHECK_SUM_DURATION = Histogram.build().name("check_sum_duration").help("Check sum duration").labelNames("type").register();

    private final String checkSumFilePath;
    private final TiSession tiSession;
    private final Map<String, String> properties;

    public CheckSumJsonJob(String checkSumFilePath, TiSession tiSession, Map<String, String> properties) {
        this.checkSumFilePath = checkSumFilePath;
        this.tiSession = tiSession;
        this.properties = properties;
    }

    @Override
    public void run() {

//        String simpleCheckSum = properties.get(SIMPLE_CHECK_SUM);
//        if (!ON.equals(simpleCheckSum)) {
//            CheckSum.checkSum(checkSumFilePath, tiSession, properties);
//        } else {
//            CheckSum.doRedo(checkSumFilePath, tiSession, properties);
//        }

//        String simpleCheckSum = properties.get(SIMPLE_CHECK_SUM);
//
//        if (ON.equals(simpleCheckSum)) {
//            CheckSum.doRedo(checkSumFilePath, tiSession, properties);
//        } else {

        logger.info("Start check sum file={}", checkSumFilePath);

        int parseErr = 0; // 解析失败数
        int notInsert = 0; // Raw kv 中不存在数
        int checkSumFail = 0; // Check sum 失败数
        AtomicInteger totalLine = new AtomicInteger(0); // 文件行数

        RawKVClient rawKvClient = tiSession.createRawClient();

        File checkSumFile = new File(checkSumFilePath);

        // Check sum 进度 timer
        Timer timer = new Timer();
        CheckSumTimer checkSumTimer = new CheckSumTimer(checkSumFilePath, totalLine, FileUtil.getFileLines(checkSumFile));
        timer.schedule(checkSumTimer, 5000, Long.parseLong(properties.get(TIMER_INTERVAL)));

        IndexInfo indexInfoRawKv, indexInfoOriginal, rawKvIndexInfoValue;
        TempIndexInfo tempIndexInfoRawKv, tempIndexInfoOriginal, rawKvTempIndexInfoValue;
        Map<String, IndexInfo> originalIndexInfoMap = new HashMap<>(), rawKvIndexInfoMap = new HashMap<>();
        Map<String, TempIndexInfo> originalTempIndexInfoMap = new HashMap<>(), rawKvTempIndexInfoMap = new HashMap<>();
        List<ByteString> keyList = new ArrayList<>();
        List<Kvrpcpb.KvPair> kvList = new ArrayList<>();
        JSONObject jsonObject;
        String key, value, envId, checkSumFileLine, tempLine;
        int limit = 0, totalCount = 0;
        int limitSize = Integer.parseInt(properties.get(CHECK_SUM_LIMIT));
        String delimiter1 = properties.get(DELIMITER_1);
        String delimiter2 = properties.get(DELIMITER_2);
        int lineCount = FileUtil.getFileLines(checkSumFile); // 数据文件行数

        LineIterator originalLineIterator = FileUtil.createLineIterator(checkSumFile);
        if (originalLineIterator != null) {
            while (originalLineIterator.hasNext()) {
                Histogram.Timer parseTimer = CHECK_SUM_DURATION.labels("parse").startTimer();
                limit++;
                totalCount++;
                totalLine.addAndGet(1);
                checkSumFileLine = originalLineIterator.nextLine();
                tempLine = checkSumFileLine.replaceAll(DELETE, "");// 去掉 delete 的 json
                switch (properties.get(MODE)) {
                    case JSON_FORMAT:
                        try {
                            // 解析数据文件为 json object
                            jsonObject = JSONObject.parseObject(tempLine);
                        } catch (Exception e) {
                            // 直接跳过
                            parseErr++;
                            continue;
                        }
                        switch (properties.get(SCENES)) {
                            case INDEX_INFO:
                                // 原始文件 indexInfo
                                indexInfoOriginal = JSON.toJavaObject(jsonObject, IndexInfo.class);
                                // 是否配置 envId 而赋值
                                if (!properties.get(ENV_ID).isEmpty()) {
                                    envId = properties.get(ENV_ID);
                                } else {
                                    envId = indexInfoOriginal.getEnvId();
                                }
                                // 解析 indexInfo key
                                key = String.format(IndexInfo.KET_FORMAT, envId, indexInfoOriginal.getType(), indexInfoOriginal.getId());
                                // delete 具有前缀
                                if (checkSumFileLine.startsWith(DELETE)) {
                                    value = rawKvClient.get(ByteString.copyFromUtf8(key)).toStringUtf8();
                                    // 如果 delete 的数据存在，则需要判断当前数据的时间戳和 delete 的时间戳做对比
                                    // 如果 TiKV > delete 则校验失败，若 delete > tikv，则正常。
                                    if (!value.isEmpty()) {
                                        indexInfoRawKv = JSONObject.parseObject(value, IndexInfo.class);
                                        if (CountUtil.compareTime(indexInfoRawKv.getUpdateTime(), indexInfoOriginal.getUpdateTime()) <= 0) {
                                            logger.error("Check sum key={} delete fail, rawkv tso={} > delete tso={}", key, indexInfoRawKv.getUpdateTime(), indexInfoOriginal.getUpdateTime());
                                            checkSumFail++;
                                        }
                                    }
                                    continue;
                                }
                                // map 组成为 key 和 value 的对象
                                originalIndexInfoMap.put(key, indexInfoOriginal);
                                keyList.add(ByteString.copyFromUtf8(key));
                                break;
                            case TEMP_INDEX_INFO:
                                // 原始文件 tempIndexInfo
                                tempIndexInfoOriginal = JSON.toJavaObject(jsonObject, TempIndexInfo.class);
                                if (!properties.get(ENV_ID).isEmpty()) {
                                    envId = properties.get(ENV_ID);
                                } else {
                                    envId = tempIndexInfoOriginal.getEnvId();
                                }
                                // 解析 tempIndexInfo key
                                key = String.format(TempIndexInfo.KEY_FORMAT, envId, tempIndexInfoOriginal.getId());
                                if (checkSumFileLine.startsWith(DELETE)) {
                                    value = rawKvClient.get(ByteString.copyFromUtf8(key)).toStringUtf8();
                                    // tempIndexInfo 没有时间戳，如果 key 还存在则直接 check sum 失败
                                    if (!value.isEmpty()) {
                                        logger.error("Check sum key={} delete fail.", key);
                                        checkSumFail++;
                                    }
                                    continue;
                                }
                                originalTempIndexInfoMap.put(key, tempIndexInfoOriginal);
                                keyList.add(ByteString.copyFromUtf8(key));
                                break;
                            default:
                        }
                        break;
                    case CSV_FORMAT:
                        indexInfoOriginal = new IndexInfo();
                        IndexInfo.csv2IndexInfo(indexInfoOriginal, checkSumFileLine, delimiter1, delimiter2);
                        key = String.format(IndexInfo.KET_FORMAT, indexInfoOriginal.getEnvId(), indexInfoOriginal.getType(), indexInfoOriginal.getId());
                        originalIndexInfoMap.put(key, indexInfoOriginal);
                        break;
                    default:
                }
                parseTimer.observeDuration();
                if (limit == limitSize || totalCount == lineCount) {
                    try {
                        Histogram.Timer batchGetTimer = CHECK_SUM_DURATION.labels("batch_get").startTimer();
                        kvList = rawKvClient.batchGet(keyList);
                        batchGetTimer.observeDuration();
                    } catch (Exception e) {
                        logger.error("Batch get failed. file={}, line={}", checkSumFile.getAbsolutePath(), totalCount);
                    }

                    Histogram.Timer csTimer = CHECK_SUM_DURATION.labels("check_sum").startTimer();
                    switch (properties.get(MODE)) {
                        case JSON_FORMAT:
                            switch (properties.get(SCENES)) {
                                case INDEX_INFO:
                                    // 将查询出的 value 转换为 indexInfo value, put key 和 indexInfo value
                                    for (Kvrpcpb.KvPair kvPair : kvList) {
                                        indexInfoRawKv = JSONObject.parseObject(kvPair.getValue().toStringUtf8(), IndexInfo.class);
                                        rawKvIndexInfoMap.put(kvPair.getKey().toStringUtf8(), indexInfoRawKv);
                                    }
                                    // 循环原始文件 map<String, indexInfo>
                                    for (Map.Entry<String, IndexInfo> originalIndexInfoKv : originalIndexInfoMap.entrySet()) {
                                        rawKvIndexInfoValue = rawKvIndexInfoMap.get(originalIndexInfoKv.getKey());
                                        if (rawKvIndexInfoValue != null) {
                                            if (!rawKvIndexInfoValue.equals(originalIndexInfoKv.getValue())) {
                                                checkSumLog.error("Check sum failed! Key={}", originalIndexInfoKv.getKey());
                                                checkSumFail++;
                                            }
                                        } else {
                                            checkSumLog.error("Key={} is not exists.", originalIndexInfoKv.getKey());
                                            notInsert++;
                                        }
                                    }
                                    break;
                                case TEMP_INDEX_INFO:
                                    for (Kvrpcpb.KvPair kvPair : kvList) {
                                        tempIndexInfoRawKv = JSONObject.parseObject(kvPair.getValue().toStringUtf8(), TempIndexInfo.class);
                                        rawKvTempIndexInfoMap.put(kvPair.getKey().toStringUtf8(), tempIndexInfoRawKv);
                                    }
                                    for (Map.Entry<String, TempIndexInfo> originalKv : originalTempIndexInfoMap.entrySet()) {
                                        rawKvTempIndexInfoValue = rawKvTempIndexInfoMap.get(originalKv.getKey());
                                        if (rawKvTempIndexInfoValue != null) {
                                            if (!rawKvTempIndexInfoValue.equals(originalKv.getValue())) {
                                                checkSumLog.error("Check sum failed. Key={}", originalKv.getKey());
                                                checkSumFail++;
                                            }
                                        } else {
                                            checkSumLog.error("Key={} is not exists.", originalKv.getKey());
                                            notInsert++;
                                        }
                                    }
                                    break;
                                default:
                            }
                            break;
                        case CSV_FORMAT:
                            break;
                        default:
                    }
                    limit = 0;
                    kvList.clear();
                    keyList.clear();
                    originalIndexInfoMap.clear();
                    rawKvIndexInfoMap.clear();
                    originalTempIndexInfoMap.clear();
                    rawKvTempIndexInfoMap.clear();
                    csTimer.observeDuration();
                }
            }
        }
        timer.cancel();
        logger.info("Check sum file={} complete. Total={}, notExists={}, skipParseErr={}, checkSumFail={}", checkSumFile.getAbsolutePath(), totalLine, notInsert, parseErr, checkSumFail);
    }
}

//}
