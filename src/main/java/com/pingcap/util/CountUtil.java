package com.pingcap.util;

import com.pingcap.enums.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author yuyang
 */
public class CountUtil {

    private static final Logger logger = LoggerFactory.getLogger(Model.LOG);

    private static final String TOTAL_LOG = " The total number of lines in the '%s' is %s, and each thread processes %s, and the remainder is %s";

    public static List<String> getPerThreadFileLines(long line, int threadNum, String fileName) {
        // Average number of items processed by each thread
        long avg = line / threadNum;
        long remainder = line % threadNum;
        logger.info(String.format(TOTAL_LOG, fileName, line, avg, remainder));
        List<String> list = new ArrayList<>();
        long startIndex; // The index at which each thread started processing.
        long todo; // How many.
        for (int i = 0; i < threadNum; i++) {
            startIndex = i * avg;
            todo = avg;
            if (i == threadNum - 1) {
                todo = todo + remainder;
                list.add(startIndex + "," + todo);
            } else {
                list.add(startIndex + "," + todo);
            }
        }
        return list;
    }

    public static String getPercentage(int n1, int n2) {
        java.text.NumberFormat numerator = java.text.NumberFormat.getInstance();
        numerator.setMaximumFractionDigits(2);
        return numerator.format(((float) n1 / (float) n2) * 100);
    }

}
