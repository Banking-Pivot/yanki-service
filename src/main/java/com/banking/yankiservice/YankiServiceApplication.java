package com.banking.yankiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts Yanki Service, the mobile wallet of the bank: wallets, their balances and the payments
 * they send each other by mobile number.
 */
@SpringBootApplication
public class YankiServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(YankiServiceApplication.class, args);
  }
}
