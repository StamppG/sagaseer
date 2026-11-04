package com.sagaseer;

import java.util.Set;

/** The account type varbit, read with Tutorial Island in mind: a choice made there is not final yet. */
final class AccountTypeResolution
{
    enum Status
    {
        UNRESOLVED_ONBOARDING,
        PROVISIONAL_ONBOARDING,
        RESOLVED,
        UNKNOWN
    }

    private static final Set<Integer> TUTORIAL_ISLAND_REGIONS = Set.of(
            12336,
            12335,
            12592,
            12080,
            12079,
            12436
    );

    /** The varbit's known values: normal, ironman, ultimate, hardcore, group, hardcore group, unranked group. */
    private static final int ACCOUNT_TYPES = 7;

    private final Status status;
    private final Integer rawAccountType;

    private AccountTypeResolution(Status status, Integer rawAccountType)
    {
        this.status = status;
        this.rawAccountType = rawAccountType;
    }

    static AccountTypeResolution resolve(Integer rawAccountType, Integer regionId)
    {
        if (rawAccountType == null || regionId == null || rawAccountType < 0 || rawAccountType >= ACCOUNT_TYPES)
        {
            return new AccountTypeResolution(Status.UNKNOWN, rawAccountType);
        }
        if (!TUTORIAL_ISLAND_REGIONS.contains(regionId))
        {
            return new AccountTypeResolution(Status.RESOLVED, rawAccountType);
        }
        return new AccountTypeResolution(rawAccountType == 0
                ? Status.UNRESOLVED_ONBOARDING : Status.PROVISIONAL_ONBOARDING, rawAccountType);
    }

    Status getStatus()
    {
        return status;
    }

    Integer getRawAccountType()
    {
        return rawAccountType;
    }
}
