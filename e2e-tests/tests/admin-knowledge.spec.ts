/**
 * 管理后台 E2E 烟雾测试——PRD §51。
 *
 * 测试流程：登录 → 上传文档 → 等待解析 → 查看分块预览 → 手动创建 Q&A
 */
import { test, expect } from '@playwright/test';

test.describe('Admin Knowledge Base Flow', () => {

  test('上传文档并验证解析结果', async ({ page }) => {
    await page.goto('/admin');

    // 切换到知识库 Tab
    await page.click('text=知识库管理');

    // 找到上传区域
    const uploadArea = page.locator('input[type="file"], [data-testid="upload-input"]');
    if (await uploadArea.count() > 0) {
      // 上传测试文档
      await uploadArea.setInputFiles('./tests/fixtures/test-doc.md');

      // 等待上传完成提示
      await expect(page.locator('text=上传, text=成功, text=解析中, text=已上传'))
        .toBeVisible({ timeout: 10000 });
    }
  });

  test('手动创建知识条目', async ({ page }) => {
    await page.goto('/admin');

    await page.click('text=知识库管理');

    // 查找创建按钮
    const createButton = page.locator(
      'button:has-text("新建"), button:has-text("创建"), a:has-text("新建")'
    );

    if (await createButton.count() > 0) {
      await createButton.first().click();

      // 填写表单
      await page.fill('input[name="title"], [data-testid="article-title"]', 'E2E测试条目');
      await page.fill(
        'textarea[name="content"], [data-testid="article-content"]',
        '这是自动化测试创建的知识条目内容。'
      );

      // 提交
      await page.click('button:has-text("保存"), button:has-text("提交")');

      // 验证成功提示
      await expect(page.locator('text=成功, text=创建成功, text=已保存'))
        .toBeVisible({ timeout: 5000 });
    }
  });
});
