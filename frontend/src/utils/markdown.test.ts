import { describe, it, expect } from 'vitest'
import { renderMarkdown } from './markdown'

// markdown-it 配置守卫——配置即安全决策（html:false 防模型输出注入），
// 故以单测钉死配置：原始 HTML 必须转义、基本语法生效、URL 自动链接。
describe('renderMarkdown', () => {
  it('escapes raw HTML tags (security: model output cannot inject script)', () => {
    const out = renderMarkdown('<script>alert(1)</script>')
    expect(out).not.toContain('<script>')
    expect(out).toContain('&lt;script&gt;')
  })

  it('renders bold emphasis', () => {
    const out = renderMarkdown('**hi**')
    expect(out).toContain('<strong>hi</strong>')
  })

  it('linkifies bare URLs', () => {
    const out = renderMarkdown('see https://example.com here')
    expect(out).toContain('<a href="https://example.com"')
  })

  it('renders empty input without throwing', () => {
    expect(renderMarkdown('')).toBe('')
  })
})
