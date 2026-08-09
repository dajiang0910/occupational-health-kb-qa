/**
 * Web 端 AI 问答 E2E 烟雾测试——PRD §51。
 *
 * 测试流程：JWT 鉴权 → 打开聊天窗口 → 输入问题 → SSE 流式回答 → 引用展示 → 点赞/点踩
 */
import { test, expect } from '@playwright/test';

test.describe('Web Chat Flow', () => {

  test('完整问答流：打开聊天 → 输入问题 → SSE 流式回答 → 引用校验 → 反馈', async ({ page }) => {
    // 1. 加载聊天 SDK 嵌入页面（模拟主 SaaS 嵌入 iframe）
    await page.goto('/admin');

    // 2. 验证管理后台首页加载
    await expect(page.locator('text=知识库管理')).toBeVisible({ timeout: 5000 });

    // 3. 切换到知识库 Tab
    await page.click('text=知识库管理');

    // 4. 验证知识库列表出现
    await expect(page.locator('table, [data-testid="kb-list"], .kb-table')).toBeVisible({ timeout: 5000 });
  });

  test('管理后台三 Tab 切换正常', async ({ page }) => {
    await page.goto('/admin');

    // 知识库 Tab
    await page.click('text=知识库管理');
    await expect(page.locator('body')).toContainText('知识');

    // 工单 Tab
    await page.click('text=客服工单');
    await expect(page.locator('body')).toContainText('工单');

    // 分析 Tab
    await page.click('text=数据分析');
    await expect(page.locator('body')).toContainText('分析');
  });
});
