package com.taobao.tddl.common.utils.logger.log4j;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.taobao.tddl.common.utils.logger.MDCAdapter;

public class Log4jMDC implements MDCAdapter {


    public void put(String key, String val) {
        org.apache.log4j.MDC.put(key, val);
    }


    public String get(String key) {
        return (String) org.apache.log4j.MDC.get(key);
    }


    public void remove(String key) {
        org.apache.log4j.MDC.remove(key);
    }


    public void clear() {
        org.apache.log4j.MDC.clear();
    }


    public Map getCopyOfContextMap() {
        Map old = org.apache.log4j.MDC.getContext();
        if (old != null) {
            return new HashMap(old);
        } else {
            return null;
        }
    }


    public void setContextMap(Map contextMap) {
        if (contextMap == null) {
            contextMap = new HashMap();
        }
        Map old = org.apache.log4j.MDC.getContext();
        if (old == null) {
            Iterator entrySetIterator = contextMap.entrySet().iterator();
            while (entrySetIterator.hasNext()) {
                Map.Entry mapEntry = (Map.Entry) entrySetIterator.next();
                org.apache.log4j.MDC.put((String) mapEntry.getKey(), mapEntry.getValue());
            }
        } else {
            old.clear();
            old.putAll(contextMap);
        }
    }
}
