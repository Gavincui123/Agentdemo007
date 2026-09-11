import MarkdownIt from 'markdown-it'

/**
 * Markdown 渲染器（Phase 16·TDD）。
 *
 * <p>模型回复按 Markdown 渲染。<b>安全决策</b>：{@code html:false}（默认）——
 * 模型输出的原始 HTML 标签一律转义，杜绝 &lt;script&gt; 注入。{@code linkify} 自动识别裸 URL。
 * 渲染产物交由 {@code v-html} 注入；转义在前，故无 XSS 面。
 */
const md = new MarkdownIt({ html: false, linkify: true, breaks: false })

/** 把 Markdown 文本渲染为 HTML 片段；空串返回空串（不抛）。 */
export function renderMarkdown(text: string): string {
  if (!text) return ''
  return md.render(text)
}
