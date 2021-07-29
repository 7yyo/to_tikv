package com.pingcap.util;

import com.pingcap.enums.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class CountUtil {

    private static final Logger logger = LoggerFactory.getLogger(Model.LOG);

    public static List<String> getPerThreadFileLines(long line, int threadNum, String fileName) {
        // Average number of items processed by each thread
        long avg = line / threadNum;
        long remainder = line % threadNum;
        logger.info("The total number of lines in the {}={}, and each thread processes={}, and the remainder={}", fileName, line, avg, remainder);
        List<String> list = new ArrayList<>();
        long startIndex; // The index at which each thread started processing.
        long todo; // How many.
        for (int i = 0; i < threadNum; i++) {
            startIndex = i * avg;
            todo = avg;
            if (i == threadNum - 1) {
                todo = todo + remainder;
            }
            list.add(startIndex + "," + todo);
        }
        return list;
    }

    /**
     * Returns the percentage of two numbers
     *
     * @param n1: Molecular
     * @param n2: Denominator
     * @return percentage
     */
    public static String getPercentage(int n1, int n2) {
        java.text.NumberFormat numerator = java.text.NumberFormat.getInstance();
        numerator.setMaximumFractionDigits(2);
        return numerator.format(((float) n1 / (float) n2) * 100);
    }

    public static int compareTime(String d1, String d2) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        int result = 0;
        try {
            Date date1 = simpleDateFormat.parse(d1);
            Date date2 = simpleDateFormat.parse(d2);
            result = date1.compareTo(date2);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return Integer.compare(result, 0);
    }

}
