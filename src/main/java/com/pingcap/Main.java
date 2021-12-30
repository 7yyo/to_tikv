package com.pingcap;

import com.pingcap.controller.ExeFactory;
import com.pingcap.enums.Model;
import com.pingcap.metrics.Prometheus;
import com.pingcap.rawkv.RawKv;
import com.pingcap.util.*;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.TiSession;

import java.util.Map;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Model.LOG);
    private static final String PERSONAL_PROPERTIES_PATH = "src/main/resources/rawkv.properties";
    private static TiSession tiSession = null;
    public static void main(String[] args) throws Exception {

        logger.info("Welcome to TiKV Migration tool!");

        String propertiesPath = System.getProperty(Model.P) == null ? PERSONAL_PROPERTIES_PATH : System.getProperty(Model.P);
        Map<String, String> properties = PropertiesUtil.getProperties(propertiesPath);
        ExeFactory.checkEnv(properties);
        tiSession = TiSessionUtil.getTiSession(properties);
        moniterShutDown(tiSession);
        //TiSession tiSession = null;
        if (!StringUtils.isEmpty(System.getProperty(Model.M))) {
            switch (System.getProperty(Model.M)) {
                case Model.GET:
                    RawKv.get(tiSession, System.getProperty(Model.K));
                    break;
                case Model.TRUNCATE:
                    RawKv.truncateRawKv(tiSession);
                    break;
                case Model.DELETE:
                    RawKv.deleteByKey(tiSession, System.getProperty(Model.K));
                    break;
                case Model.DELETE_BY_PREFIX:
                    RawKv.deleteByPrefix(tiSession, System.getProperty(Model.K));
                    break;
                default:
                    throw new IllegalStateException(System.getProperty(Model.M));
            }
        } else {

            String task = properties.get(Model.TASK);

            if (properties.get(Model.PROMETHEUS_ENABLE) != null) {
                if (Model.ON.equals(properties.get(Model.PROMETHEUS_ENABLE))) {
                    PropertiesUtil.checkConfig(properties, Model.PROMETHEUS_PORT);
                    Prometheus.initPrometheus(Integer.parseInt(properties.get(Model.PROMETHEUS_PORT)));
                }
            }

           	ExeFactory.getInstance(tiSession,task,properties).run();
        }

        logger.info("Goodbye TiKV Migration tool!");
        System.exit(0);

    }

    public static void moniterShutDown(final TiSession tiSession){
	  Runtime.getRuntime().addShutdownHook(new Thread() {
		   @Override
		   public void run() {
			   if(null != tiSession){
				   try {
					tiSession.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			   }
		   }
	  });
    }
}
