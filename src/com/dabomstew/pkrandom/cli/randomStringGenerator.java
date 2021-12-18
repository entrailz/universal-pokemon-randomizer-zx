package com.dabomstew.pkrandom.cli;

import java.util.UUID;

public class randomStringGenerator {
    public static void main(String[] args) {
        System.out.println(generateString());
    }

    public static String generateString() {
        String uuid = UUID.randomUUID().toString();
        return uuid;
    }
}
