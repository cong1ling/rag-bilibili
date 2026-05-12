package com.example.ragcsdn.service.article;

public enum BatchImportStatus {
    SUBMITTED("SUBMITTED", "已提交导入任务"),
    SKIPPED_DUPLICATE("SKIPPED_DUPLICATE", "该文章已导入"),
    FAILED("FAILED", "批量导入失败");

    private final String code;
    private final String defaultMessage;

    BatchImportStatus(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
