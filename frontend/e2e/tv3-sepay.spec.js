import { test, expect } from '@playwright/test';
import { createHmac, randomUUID } from 'node:crypto';

// ADMIN accounts land on /admin after login; customers and staff return to the cart.
const landingAfterLogin = username => username === (process.env.TV3_ADMIN_USER || 'tv3.admin') ? /\/admin$/ : /\/cart$/;
async function login(page,username){
  await page.goto('/login');await page.getByLabel('Tên đăng nhập',{exact:true}).fill(username);
  await page.getByLabel('Mật khẩu',{exact:true}).fill(process.env.TV3_DEMO_PASSWORD);
  await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();await expect(page).toHaveURL(landingAfterLogin(username));
}

test('SePay QR checkout verifies signed transfers once and permits manual refund proof after cancellation',async({page,request,browser,baseURL})=>{
  test.setTimeout(120000);
  test.skip(!process.env.TV3_DEMO_PASSWORD||!process.env.SEPAY_WEBHOOK_SECRET,'Provide isolated demo credentials and a sandbox-only HMAC key.');
  expect((await(await request.get('/api/commerce/runtime')).json()).isolatedData).toBe(true);
  expect((await(await request.get('/api/payments/sepay/config')).json()).enabled).toBe(true);
  page.setDefaultTimeout(10000);
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setViewportSize({width:1440,height:1000});await login(page,'tv3.customer');
  if(await page.getByRole('button',{name:'Xóa tất cả',exact:true}).isVisible()){
    await page.getByRole('button',{name:'Xóa tất cả',exact:true}).click();await page.getByRole('button',{name:'Đồng ý xóa hết',exact:true}).click();
  }
  await page.getByRole('button',{name:'Thêm Bàn trà Oslo vào giỏ',exact:true}).click();await page.getByRole('link',{name:'Tiếp tục đặt hàng',exact:true}).click();
  await page.getByLabel('Nhập địa chỉ mới',{exact:true}).check();
  for(const [label,value] of [['Họ tên người nhận','SePay Browser TEST'],['Số điện thoại','0900000000'],['Địa chỉ','12 Test Sandbox'],['Phường / xã','Phường 1'],['Quận / huyện','Quận 3'],['Tỉnh / thành phố','Hồ Chí Minh']])await page.getByLabel(label,{exact:true}).fill(value);
  await page.getByLabel('SePay Sandbox · QR thử nghiệm',{exact:true}).check();
  await page.getByRole('button',{name:'Xem trước đơn hàng',exact:true}).click();await page.getByLabel('Tôi đã kiểm tra thông tin và tổng tiền.',{exact:true}).check();
  const placed=page.waitForResponse(r=>r.url().endsWith('/api/checkout/orders')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'Đặt hàng',exact:true}).click();const creation=await placed;expect(creation.status()).toBe(201);const order=await creation.json();
  await expect(page).toHaveURL(new RegExp(`/orders/${order.id}/payments$`));
  await expect(page.getByRole('img',{name:/QR thanh toán SePay/})).toBeVisible();
  await expect.poll(()=>page.getByRole('img',{name:/QR thanh toán SePay/}).evaluate(img=>img.complete&&img.naturalWidth>0)).toBe(true);
  let summary=await(await page.request.get(`/api/orders/${order.id}/payments`)).json();const payment=summary.items[0];
  expect(payment.sepay.transferContent).toMatch(/^SEVQR DH[0-9]{8}$/);
  await page.screenshot({path:'test-results/tv3-sepay-qr-desktop.png',fullPage:true});await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await page.screenshot({path:'test-results/tv3-sepay-qr-mobile.png',fullPage:true});
  const id=String(Date.now()),reference='E2E-'+randomUUID();
  const transfer={id,gateway:payment.sepay.bank,transactionDate:new Date().toISOString().slice(0,19).replace('T',' '),accountNumber:payment.sepay.accountNumber,subAccount:null,code:payment.sepay.code,content:payment.sepay.transferContent,transferType:'in',transferAmount:Number(payment.amount),referenceCode:reference};
  async function deliver(value){const body=JSON.stringify(value),timestamp=String(Math.floor(Date.now()/1000));return request.post('/api/payments/sepay/webhook',{headers:{'Content-Type':'application/json','X-SePay-Timestamp':timestamp,'X-SePay-Signature':'sha256='+createHmac('sha256',process.env.SEPAY_WEBHOOK_SECRET).update(timestamp+'.'+body).digest('hex')},data:body});}
  expect((await deliver({...transfer,id:id+'1',transferAmount:transfer.transferAmount+1})).status()).toBe(200);
  summary=await(await page.request.get(`/api/orders/${order.id}/payments`)).json();expect(summary.items[0].status).toBe('PENDING');
  expect((await deliver(transfer)).status()).toBe(200);expect((await deliver(transfer)).status()).toBe(200);
  await page.getByRole('button',{name:'Làm mới thanh toán',exact:true}).click();await expect(page.getByTestId('payment-remaining')).toHaveText('0 ₫');await expect(page.getByRole('img',{name:/QR thanh toán SePay/})).toHaveCount(0);
  const csrf=await(await page.request.get('/api/csrf')).json();const cancelled=await page.request.post(`/api/orders/${order.id}/cancel`,{headers:{[csrf.headerName]:csrf.token},data:{reason:'Sandbox browser verification complete'}});expect(cancelled.ok()).toBe(true);
  const context=await browser.newContext({baseURL});const admin=await context.newPage();await login(admin,'tv3.admin');
  const refunds=await(await admin.request.get(`/api/admin/orders/${order.id}/refunds`)).json();expect(refunds.items).toHaveLength(1);
  const refund=refunds.items[0];expect(refund.provider).toBe('SEPAY_SANDBOX');
  await admin.goto(`/admin/refunds/${refund.id}`);await admin.getByLabel('Tham chiếu hoàn tiền',{exact:true}).fill('SANDBOX-REFUND-'+order.id);
  await admin.getByLabel('Tôi đã đối chiếu chứng từ và xác nhận đã trả đủ số tiền này.',{exact:true}).check();await admin.getByRole('button',{name:'Kiểm tra chứng từ hoàn',exact:true}).click();
  await expect(admin.getByRole('button',{name:'Xác nhận đã hoàn tiền',exact:true})).toBeEnabled();await admin.getByRole('button',{name:'Xác nhận đã hoàn tiền',exact:true}).click();
  await expect(admin.getByText('Đã ghi nhận chứng từ hoàn tiền.',{exact:true})).toBeVisible();await context.close();expect(errors).toEqual([]);
});
