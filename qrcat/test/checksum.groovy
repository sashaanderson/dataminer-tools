#!/usr/bin/env groovy

import java.security.MessageDigest

def md5 = MessageDigest.getInstance("MD5")

while (true) {
    byte[] chunk = System.in.readNBytes(2000)
    if (!chunk)
        break
    println(md5.digest(chunk).encodeHex().toString())
}
