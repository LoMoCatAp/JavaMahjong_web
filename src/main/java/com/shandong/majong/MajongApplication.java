package com.shandong.majong;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/*Java面向对象程序设计课程设计 麻将*/
@SpringBootApplication
public class MajongApplication {

    public static void main(String[] args) {
        SpringApplication.run(MajongApplication.class, args);
        System.out.println("山 东 麻 将 服 务 器 已 启 动");
        System.out.println("访问地址: http://localhost:8080");
    }
}
