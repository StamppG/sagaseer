package com.sagaseer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Local per-profile character key: a hash of the RuneLite profile id and account name, used to
 * keep per-character local state apart. The Backend alone decides character ownership.
 */
final class CharacterKey
{
    private CharacterKey() { }

    static String key(long runeLiteProfileId, String accountName)
    {
        if (accountName == null || accountName.isBlank() || accountName.length() > 64 ||
                accountName.chars().anyMatch(Character::isISOControl)) return null;
        String material = "sagaseer-character-v1\n" + runeLiteProfileId + "\n" +
                accountName.trim().toLowerCase(Locale.ROOT);
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("p2:");
            for (byte value : digest)
            {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
