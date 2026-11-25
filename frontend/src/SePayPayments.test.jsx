import React from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { emptyCart, guest, staff } from './test/fixtures';
import { customerSession, payment, paymentSummary, paidSummary, paymentList, staffPayment, staffPaymentSummary } from './test/paymentFixtures';

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });
function mount(path = '/orders/101/payments', handlers = {}, session = customerSession) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET'; calls.push({ url, ...options, method });
    const handler = handlers[`${method} ${url}`]; let value;
    if (handler) value = typeof handler === 'function' ? await handler(options) : handler;
    else if (url === '/api/session') value = session;
    else if (url === '/api/csrf') value = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/session/login') value = session.authenticated ? session : staff;
    else if (url === '/api/cart/merge') value = emptyCart;
    else if (url === '/api/orders/101/payments') value = paymentSummary;
    else if (url === '/api/admin/payments/31') value = staffPaymentSummary;
    else if (url.startsWith('/api/admin/payments?')) value = paymentList;
    else throw new Error(`Unexpected HTTP ${method} ${url}`);
    if (value instanceof Response) return value;
    return new Response(JSON.stringify(value.body ?? value), { status: Object.hasOwn(value, 'body') ? value.status : 200, headers: { 'Content-Type': 'application/json' } });
  }));
  return { ...render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>), calls, user: userEvent.setup() };
}

const pendingSePay = { ...payment, method: 'BANK_TRANSFER', provider: 'SEPAY_SANDBOX', sepay: { environment: 'SANDBOX', code: 'DH12345678', bank: 'VietinBank', accountNumber: '0000000001', accountHolder: 'TEST ACCOUNT', transferContent: 'SEVQR DH12345678', qrUrl: '/api/orders/101/payments/31/sepay/qr', canReconcile: true }, canConfirm: false };
const sepaySummary = { ...paymentSummary, sepay: { enabled: true, environment: 'SANDBOX', canReconcile: true }, items: [pendingSePay], canCreate: false, createBlockReason: 'PAYMENT_PENDING_EXISTS' };
it('shows a clearly labelled test QR with server amount and never asks the browser to confirm payment', async () => {
 const { calls } = mount('/orders/101/payments', { 'GET /api/orders/101/payments': sepaySummary });
 expect(await screen.findByRole('img', {name: /QR thanh toán SePay/})).toHaveAttribute('src', pendingSePay.sepay.qrUrl);
 expect(screen.getByText('SEVQR DH12345678')).toBeInTheDocument();
 expect(screen.getByRole('heading', {name: 'SePay Sandbox · Không chuyển tiền thật'})).toBeInTheDocument();
 expect(screen.queryByRole('button', {name: 'Xác nhận đã thu tiền'})).not.toBeInTheDocument();
 expect(calls.filter(c => c.method === 'POST')).toHaveLength(0);
});
it('removes the QR after server confirms collection', async () => {
 let reads=0;
 const {user} = mount('/orders/101/payments', {'GET /api/orders/101/payments': () => ++reads===1 ? sepaySummary : {...sepaySummary, remainingAmount:'0.00',collectedAmount:'2550000.00',items:[{...pendingSePay,status:'SUCCEEDED',sepay:{...pendingSePay.sepay,qrUrl:undefined}}]}});
 await screen.findByRole('img', {name: /QR thanh toán SePay/});await user.click(screen.getByRole('button',{name:'Làm mới thanh toán'}));
 await waitFor(()=>expect(screen.queryByRole('img',{name:/QR thanh toán SePay/})).not.toBeInTheDocument());expect(screen.getByTestId('payment-remaining')).toHaveTextContent('0 ₫');
});
it('creates SePay with the persisted key and keeps it when response is lost', async()=>{
 const {user,calls}=mount('/orders/101/payments',{'GET /api/orders/101/payments':{...sepaySummary,items:[],canCreate:true,createBlockReason:null},'POST /api/orders/101/payments':()=>{throw new TypeError('Connection lost');}});
 await user.selectOptions(await screen.findByLabelText('Phương thức yêu cầu mới'),'SEPAY');await user.click(screen.getByRole('button',{name:'Tạo yêu cầu thanh toán'}));
 await screen.findByRole('button',{name:'Thử lại yêu cầu thanh toán'});await user.click(screen.getByRole('button',{name:'Thử lại yêu cầu thanh toán'}));
 const posts=calls.filter(c=>c.method==='POST'&&c.url==='/api/orders/101/payments');expect(posts).toHaveLength(2);expect(posts[0].headers['Idempotency-Key']).toBe(posts[1].headers['Idempotency-Key']);expect(JSON.parse(posts[0].body)).toEqual({method:'SEPAY'});
});
it('staff reconciles a SePay UUID through the server without exposing a manual confirmation form',async()=>{
 const staffView={...sepaySummary,selectedPaymentId:31,items:[{...pendingSePay,id:31}],sepayEvents:[]};
 const {user,calls}=mount('/admin/payments/31',{'GET /api/admin/payments/31':staffView,'POST /api/admin/payments/31/sepay/reconcile':{...staffView,reconciliation:{processingStatus:'PROCESSED'},items:[{...pendingSePay,id:31,status:'SUCCEEDED'}]}},staff);
 await user.type(await screen.findByLabelText('UUID giao dịch SePay'),'a1b2c3d4-e5f6-7890-abcd-ef1234567890');await user.click(screen.getByRole('button',{name:'Đối soát với SePay'}));
 await screen.findByText(/Đã đối soát giao dịch/);expect(screen.queryByLabelText('Mã chứng từ / tham chiếu')).not.toBeInTheDocument();expect(calls.some(c=>c.url.endsWith('/sepay/reconcile')&&c.method==='POST')).toBe(true);
});

it('allows correction of a UUID that SePay definitively cannot find',async()=>{
 const view={...sepaySummary,selectedPaymentId:31,items:[{...pendingSePay,id:31}],sepayEvents:[]};
 const {user}=mount('/admin/payments/31',{'GET /api/admin/payments/31':view,'POST /api/admin/payments/31/sepay/reconcile':{status:404,body:{code:'SEPAY_TRANSACTION_NOT_FOUND',message:'Không tìm thấy giao dịch.'}}},staff);
 const input=await screen.findByLabelText('UUID giao dịch SePay');await user.type(input,'a1b2c3d4-e5f6-7890-abcd-ef1234567890');await user.click(screen.getByRole('button',{name:'Đối soát với SePay'}));await screen.findByText('Không tìm thấy giao dịch.');expect(input).toBeEnabled();await user.clear(input);await user.type(input,'00000000-0000-0000-0000-000000000001');expect(input).toHaveValue('00000000-0000-0000-0000-000000000001');
});
