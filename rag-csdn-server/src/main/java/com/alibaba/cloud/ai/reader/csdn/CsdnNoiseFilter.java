package com.alibaba.cloud.ai.reader.csdn;

import java.util.regex.Pattern;

public class CsdnNoiseFilter {
    private static final Pattern METRIC_ONLY_LINE = Pattern.compile(
            "^(点赞数?|点赞|评论数?|评论|浏览量|阅读量|浏览|阅读|收藏数?|收藏|转发数?|转发|分享数?|分享)"
                    + "(?:[：:：\\s-]*[0-9a-zA-Z.,万wW+kK]+)?$");
    private static final Pattern SHARE_LINE = Pattern.compile("^(分享至.*|分享到.*|微信扫码.*|扫码.*)$");
    private static final Pattern DIRECTORY_LINE = Pattern.compile("^(目录|文章目录|目录\\s*收起|目录\\s*展开)$");

    public boolean isNoiseLine(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        if (METRIC_ONLY_LINE.matcher(text).matches()) {
            return true;
        }
        if (SHARE_LINE.matcher(text).matches()) {
            return true;
        }
        if (DIRECTORY_LINE.matcher(text).matches()) {
            return true;
        }
        return text.startsWith("作者：")
                || text.startsWith("作者:")
                || text.startsWith("博主：")
                || text.startsWith("博主:")
                || text.startsWith("分类专栏：")
                || text.startsWith("分类专栏:")
                || text.startsWith("版权声明：")
                || text.startsWith("版权声明:")
                || text.startsWith("文章标签：")
                || text.startsWith("文章标签:");
    }
}
