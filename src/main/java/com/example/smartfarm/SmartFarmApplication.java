package com.example.smartfarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智慧农业精准灌溉系统启动类。
 *
 * 运行环境：
 *   - 默认 dev（内嵌 H2，开箱即跑）      ：mvn spring-boot:run
 *   - MySQL 环境                        ：mvn spring-boot:run -Dspring-boot.run.profiles=mysql
 */
@SpringBootApplication
@ServletComponentScan
@EnableScheduling
public class SmartFarmApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartFarmApplication.class, args);
        printStartupInfo();
    }

    private static void printStartupInfo() {
        System.out.println("\n" +
                "╔══════════════════════════════════════════════════════════╗\n" +
                "║                  智慧农业系统启动成功！                  ║\n" +
                "╠══════════════════════════════════════════════════════════╣\n" +
                "║ 登录入口      : http://localhost:8081/index.html         ║\n" +
                "║ 农户操作端    : http://localhost:8081/farmer.html        ║\n" +
                "║ 管理员后台    : http://localhost:8081/admin.html         ║\n" +
                "║ H2 控制台     : http://localhost:8081/h2-console         ║\n" +
                "╠══════════════════════════════════════════════════════════╣\n" +
                "║ 测试账号                                                 ║\n" +
                "║   管理员 : admin   / 123456                              ║\n" +
                "║   农户1  : farmer1 / 123456                              ║\n" +
                "║   农户2  : farmer2 / 123456                              ║\n" +
                "╠══════════════════════════════════════════════════════════╣\n" +
                "║ 已启用的后台任务                                         ║\n" +
                "║   · 自动灌溉巡检   每分钟一次，按策略自动执行灌溉         ║\n" +
                "║   · 设备离线巡检   每5分钟一次，离线设备触发高等级告警    ║\n" +
                "║   · 灌溉任务回收   每30秒一次，完成后回写湿度             ║\n" +
                "║   · 传感器模拟采集 每分钟一次（仅 dev 环境）              ║\n" +
                "╚══════════════════════════════════════════════════════════╝\n");
    }
}
