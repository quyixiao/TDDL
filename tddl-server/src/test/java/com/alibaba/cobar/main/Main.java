package com.alibaba.cobar.main;

/**
 * Created by Mycat on 2015/12/2.
 */
public class Main {
    public static void main(String[] args) {
        try {
            com.taobao.tddl.server.TddlLauncher.main(args);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}
