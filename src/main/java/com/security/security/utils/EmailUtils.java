package com.security.security.utils;

public class EmailUtils {

    public static String getEmailMessage(String name, String host, String key){
        return "Hello " + name + ",\n\nYour new account has been created. Please click on the link below to verify your account.\n\n"
                + getVerificationUrl(host , key) + "\n\nThe Support Team";
    }


    public static String getRestPasswordMessage(String name, String host, String token){
        return "Hello " + name + ",\n\nYour new rest password has been created. Please click on the link below to verify your new password.\n\n"
                + getResetPasswordUrl(host , token) + "\n\nThe Support Team";
    }
    private static String getVerificationUrl(String host, String key) {
        return host + "/user/verify/account?credential_key=" + key;
    }

    private static String getResetPasswordUrl(String host, String token) {
        return host + "/user/reset-password?credential_key=" + token;
    }

}