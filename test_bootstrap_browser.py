"""Optional real Chromium test with all network requests intercepted."""
import asyncio
import importlib.util
import json
import shutil
import tempfile
import unittest
from auto_bootstrap import WEB_ORIGIN, API_ORIGIN, BootstrapError, browser_fetch

@unittest.skipUnless(importlib.util.find_spec('playwright') and shutil.which('chromium'),
                     'Optional Playwright + system Chromium not installed')
class BrowserTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_fetch_and_origin_guard_with_mock_network(self):
        from playwright.async_api import async_playwright
        with tempfile.TemporaryDirectory() as profile:
            async with async_playwright() as pw:
                ctx = await pw.chromium.launch_persistent_context(
                    profile, headless=True, executable_path=shutil.which('chromium'))
                try:
                    async def route(r):
                        url = r.request.url
                        if url.startswith(WEB_ORIGIN + '/'):
                            await r.fulfill(status=200,content_type='text/html',body='<html>synthetic</html>')
                        elif url.startswith(API_ORIGIN + '/'):
                            headers={'access-control-allow-origin':WEB_ORIGIN,
                                     'access-control-allow-credentials':'true',
                                     'access-control-allow-headers':'content-type,x-encrypt-response',
                                     'access-control-allow-methods':'GET,POST,OPTIONS'}
                            if r.request.method == 'OPTIONS':
                                await r.fulfill(status=204,headers=headers)
                            else:
                                await r.fulfill(status=200,headers=headers,content_type='application/json',
                                                body=json.dumps({'status':0,'data':[]}))
                        else:
                            await r.abort()
                    await ctx.route('**/*',route)
                    page=await ctx.new_page();await page.goto(WEB_ORIGIN)
                    self.assertEqual(await browser_fetch(page,'GET','/device/list',None),{'status':0,'data':[]})
                    self.assertEqual(await browser_fetch(page,'POST','/device/sn-sign',{'sns':['synthetic']}),{'status':0,'data':[]})
                    await page.goto('about:blank')
                    with self.assertRaises(BootstrapError):
                        await browser_fetch(page,'GET','/device/list',None)
                finally:
                    await ctx.close()

if __name__=='__main__':unittest.main()
