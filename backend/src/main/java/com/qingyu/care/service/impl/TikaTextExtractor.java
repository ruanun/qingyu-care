package com.qingyu.care.service.impl;

import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

/**
 * 流式抽取文档正文，带字符上限（安全审查 M8 第二段，2026-09-03）。
 *
 * <p>为什么不用 Spring AI 的 {@code TikaDocumentReader}：它要一个 {@code Resource}，调用方
 * 只好先把整个文件读成 {@code byte[]}；内部又用 {@code BodyContentHandler(-1)}，抽出的文本
 * 没有上限。100MB 的文件 × 长任务池 6 线程就能把堆打爆，而抽出多少文本就切多少块、嵌多少次，
 * embedding 费用没有封顶。
 *
 * <p>这里直接用 Tika 的 {@link AutoDetectParser} 从流解析，{@link BodyContentHandler} 到上限
 * 即抛 {@link WriteLimitReachedException}，捕获后把已抽到的部分交出去并标记截断。
 * {@link ParseContext} 刻意不放 {@code Parser}：不递归解析嵌入文档（docx 里的图片、zip 里的
 * 文件），既省内存也少一条攻击面。
 *
 * @author ruan
 */
public class TikaTextExtractor {

    /** 抽取结果：正文 + 是否因超过上限被截断。 */
    public record ExtractedText(String text, boolean truncated) {
    }

    private final AutoDetectParser parser = new AutoDetectParser();

    /**
     * @param in       文档字节流，由调用方关闭
     * @param maxChars 正文字符上限（&gt;0）
     */
    public ExtractedText extract(InputStream in, int maxChars) {
        BodyContentHandler handler = new BodyContentHandler(maxChars);
        boolean truncated = false;
        try {
            parser.parse(in, handler, new Metadata(), new ParseContext());
        } catch (WriteLimitReachedException e) {
            truncated = true;
        } catch (SAXException e) {
            // Tika 2.x/3.x 会把 WriteLimitReachedException 包在 SAXException 里再抛一次
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                truncated = true;
            } else {
                throw new IllegalStateException("文档解析失败: " + e.getMessage(), e);
            }
        } catch (org.apache.tika.exception.ZeroByteFileException e) {
            return new ExtractedText("", false);
        } catch (IOException | org.apache.tika.exception.TikaException e) {
            throw new IllegalStateException("文档解析失败: " + e.getMessage(), e);
        }
        String text = handler.toString();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
        }
        return new ExtractedText(text, truncated);
    }
}
