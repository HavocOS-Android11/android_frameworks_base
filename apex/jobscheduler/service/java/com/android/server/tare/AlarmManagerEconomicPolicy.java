/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.tare;

import static com.android.server.tare.TareUtils.arcToNarc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;

/**
 * Policy defining pricing information and daily ARC requirements and suggestions for
 * AlarmManager.
 */
public class AlarmManagerEconomicPolicy extends EconomicPolicy {
    public static final String ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE =
            "ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE";
    public static final String ACTION_ALARM_WAKEUP_EXACT = "ALARM_WAKEUP_EXACT";
    public static final String ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE =
            "ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE";
    public static final String ACTION_ALARM_WAKEUP_INEXACT = "ALARM_WAKEUP_INEXACT";
    public static final String ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE =
            "ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE";
    public static final String ACTION_ALARM_NONWAKEUP_EXACT = "ALARM_NONWAKEUP_EXACT";
    public static final String ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE =
            "ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE";
    public static final String ACTION_ALARM_NONWAKEUP_INEXACT = "ALARM_NONWAKEUP_INEXACT";
    public static final String ACTION_ALARM_CLOCK = "ALARM_CLOCK";

    private final ArrayMap<String, Action> mActions = new ArrayMap<>();
    private final ArrayMap<String, Reward> mRewards = new ArrayMap<>();

    AlarmManagerEconomicPolicy(InternalResourceService irs) {
        super(irs);
        loadActions();
        loadRewards();
    }

    @Override
    long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        // TODO: take exemption into account
        return arcToNarc(160);
    }

    @Override
    long getMaxSatiatedBalance() {
        return arcToNarc(1440);
    }


    @Override
    long getMaxSatiatedCirculation() {
        return arcToNarc(52000);
    }

    @Nullable
    @Override
    Action getAction(@NonNull String actionName) {
        return mActions.get(actionName);
    }

    @Nullable
    @Override
    Reward getReward(@NonNull String rewardName) {
        return mRewards.get(rewardName);
    }

    private void loadActions() {
        mActions.put(ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE, arcToNarc(3), arcToNarc(5)));
        mActions.put(ACTION_ALARM_WAKEUP_EXACT,
                new Action(ACTION_ALARM_WAKEUP_EXACT, arcToNarc(3), arcToNarc(4)));
        mActions.put(ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(3), arcToNarc(4)));
        mActions.put(ACTION_ALARM_WAKEUP_INEXACT,
                new Action(ACTION_ALARM_WAKEUP_INEXACT, arcToNarc(3), arcToNarc(3)));
        mActions.put(ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(1), arcToNarc(3)));
        mActions.put(ACTION_ALARM_NONWAKEUP_EXACT,
                new Action(ACTION_ALARM_NONWAKEUP_EXACT, arcToNarc(1), arcToNarc(2)));
        mActions.put(ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(1), arcToNarc(2)));
        mActions.put(ACTION_ALARM_NONWAKEUP_INEXACT,
                new Action(ACTION_ALARM_NONWAKEUP_INEXACT, arcToNarc(1), arcToNarc(1)));
        mActions.put(ACTION_ALARM_CLOCK,
                new Action(ACTION_ALARM_CLOCK, arcToNarc(5), arcToNarc(10)));
    }

    private void loadRewards() {
        mRewards.put(REWARD_TOP_ACTIVITY,
                new Reward(REWARD_TOP_ACTIVITY,
                        arcToNarc(0), /* .01 arcs */ arcToNarc(1) / 100, arcToNarc(500)));
        mRewards.put(REWARD_NOTIFICATION_SEEN,
                new Reward(REWARD_NOTIFICATION_SEEN, arcToNarc(3), arcToNarc(0), arcToNarc(60)));
        mRewards.put(REWARD_NOTIFICATION_INTERACTION,
                new Reward(REWARD_NOTIFICATION_INTERACTION,
                        arcToNarc(5), arcToNarc(0), arcToNarc(500)));
        mRewards.put(REWARD_WIDGET_INTERACTION,
                new Reward(REWARD_WIDGET_INTERACTION, arcToNarc(10), arcToNarc(0), arcToNarc(500)));
        mRewards.put(REWARD_OTHER_USER_INTERACTION,
                new Reward(REWARD_OTHER_USER_INTERACTION,
                        arcToNarc(10), arcToNarc(0), arcToNarc(500)));
    }
}
