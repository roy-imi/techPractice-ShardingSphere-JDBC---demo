package com.example.assetinspection.domain;

/** 点巡检记录状态；数据库保存枚举名，避免数字魔法值。 */
public enum InspectionStatus {
    NORMAL,
    ABNORMAL,
    REPAIRED
}
