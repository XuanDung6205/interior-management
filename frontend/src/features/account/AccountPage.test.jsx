import { afterEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import AccountPage from './AccountPage';
import { SessionContext } from '../../lib/session';

afterEach(() => vi.restoreAllMocks());

function mount() {
  const session = { authenticated: true, user: { id: 7, username: 'customer', fullName: 'An Nguyen', roles: ['CUSTOMER'] } };
  const setSession = vi.fn();
  let profile = { id: 7, username: 'customer', fullName: 'An Nguyen', email: 'an@example.invalid', phone: '0900000000' };
  let addresses = [];
  let nextId = 1;
  const api = {
    request: vi.fn(async (path, options = {}) => {
      const method = options.method || 'GET';
      if (path === '/account/profile' && method === 'GET') return profile;
      if (path === '/account/profile' && method === 'PUT') {
        profile = {
          ...profile,
          ...options.body,
          fullName: options.body.fullName.trim(),
          email: options.body.email.toLowerCase(),
        };
        return profile;
      }
      if (path === '/account/addresses' && method === 'GET') return addresses.map(address => ({ ...address }));
      if (path === '/account/addresses' && method === 'POST') {
        const address = { id: nextId++, ...options.body, isDefault: addresses.length === 0 };
        addresses = [...addresses, address];
        return address;
      }
      const match = /^\/account\/addresses\/(\d+)(?:\/(default))?$/.exec(path);
      if (!match) throw new Error(`Unexpected API request: ${method} ${path}`);
      const id = Number(match[1]);
      if (match[2] === 'default' && method === 'PATCH') {
        addresses = addresses.map(address => ({ ...address, isDefault: address.id === id }));
        return addresses.find(address => address.id === id);
      }
      if (method === 'PUT') {
        addresses = addresses.map(address => address.id === id ? { ...address, ...options.body } : address);
        return addresses.find(address => address.id === id);
      }
      if (method === 'DELETE') {
        addresses = addresses.filter(address => address.id !== id);
        return undefined;
      }
      throw new Error(`Unexpected API request: ${method} ${path}`);
    }),
  };
  render(<MemoryRouter><SessionContext.Provider value={{ api, session, setSession }}><AccountPage /></SessionContext.Provider></MemoryRouter>);
  return { api, setSession, user: userEvent.setup() };
}

it('loads and saves the signed-in customer profile', async () => {
  const { api, setSession, user } = mount();
  const fullName = await screen.findByLabelText('Họ và tên');
  expect(fullName).toHaveValue('An Nguyen');
  await user.clear(fullName);
  await user.type(fullName, '  An Tran  ');
  await user.clear(screen.getByLabelText('Email'));
  await user.type(screen.getByLabelText('Email'), 'AN.TRAN@example.invalid');
  await user.click(screen.getByRole('button', { name: /Lưu hồ sơ/ }));
  expect(await screen.findByRole('status')).toHaveTextContent('Đã cập nhật hồ sơ');
  expect(api.request).toHaveBeenCalledWith('/account/profile', expect.objectContaining({
    method: 'PUT',
    body: { fullName: 'An Tran', email: 'AN.TRAN@example.invalid', phone: '0900000000' },
  }));
  expect(screen.getByLabelText('Họ và tên')).toHaveValue('An Tran');
  expect(screen.getByLabelText('Email')).toHaveValue('an.tran@example.invalid');
  expect(setSession).toHaveBeenCalledWith(expect.any(Function));
  expect(setSession.mock.calls[0][0]({ user: { id: 7, fullName: 'An Nguyen' } })).toEqual({
    user: { id: 7, fullName: 'An Tran' },
  });
});

it('creates, edits, changes the default and deletes saved addresses', async () => {
  const { api, user } = mount();
  await user.click(screen.getByRole('tab', { name: 'Sổ địa chỉ' }));
  expect(await screen.findByText('Bạn chưa lưu địa chỉ nhận hàng nào.')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: /Thêm địa chỉ/ }));
  let form = screen.getByRole('form', { name: 'Thêm địa chỉ' });
  await user.type(within(form).getByLabelText('Nhãn địa chỉ'), 'Nhà riêng');
  await user.type(within(form).getByLabelText('Người nhận'), 'An Nguyen');
  await user.type(within(form).getByLabelText('Số điện thoại'), '0900000000');
  await user.type(within(form).getByLabelText('Địa chỉ cụ thể'), '12 Nguyen Hue');
  await user.type(within(form).getByLabelText('Phường / Xã'), 'Ben Nghe');
  await user.type(within(form).getByLabelText('Quận / Huyện'), 'Quan 1');
  await user.type(within(form).getByLabelText('Tỉnh / Thành phố'), 'Ho Chi Minh');
  await user.click(within(form).getByRole('button', { name: 'Thêm địa chỉ' }));
  const address = await screen.findByRole('article');
  expect(within(address).getByText('Nhà riêng')).toBeInTheDocument();
  expect(within(address).getByText('Mặc định')).toBeInTheDocument();

  await user.click(within(address).getByRole('button', { name: 'Sửa' }));
  form = screen.getByRole('form', { name: 'Sửa địa chỉ' });
  const line1 = within(form).getByLabelText('Địa chỉ cụ thể');
  await user.clear(line1);
  await user.type(line1, '99 Le Loi');
  await user.click(within(form).getByRole('button', { name: 'Lưu địa chỉ' }));
  expect(await screen.findByText('99 Le Loi, Ben Nghe, Quan 1, Ho Chi Minh, Việt Nam')).toBeInTheDocument();
  expect(api.request).toHaveBeenCalledWith('/account/addresses/1', expect.objectContaining({ method: 'PUT' }));

  await user.click(screen.getByRole('button', { name: /Thêm địa chỉ/ }));
  form = screen.getByRole('form', { name: 'Thêm địa chỉ' });
  await user.type(within(form).getByLabelText('Nhãn địa chỉ'), 'Công ty');
  await user.type(within(form).getByLabelText('Người nhận'), 'An Nguyen');
  await user.type(within(form).getByLabelText('Số điện thoại'), '0900000000');
  await user.type(within(form).getByLabelText('Địa chỉ cụ thể'), '2 Le Duan');
  await user.type(within(form).getByLabelText('Tỉnh / Thành phố'), 'Ho Chi Minh');
  await user.click(within(form).getByRole('button', { name: 'Thêm địa chỉ' }));
  await screen.findByText('Công ty');
  const companyAddress = screen.getAllByRole('article').find(article => within(article).queryByText('Công ty'));
  await user.click(within(companyAddress).getByRole('button', { name: 'Đặt làm mặc định' }));
  await waitFor(() => {
    const currentAddress = screen.getAllByRole('article').find(article => within(article).queryByText('Công ty'));
    expect(within(currentAddress).getByText('Mặc định')).toBeInTheDocument();
  });
  expect(api.request).toHaveBeenCalledWith('/account/addresses/2/default', expect.objectContaining({ method: 'PATCH' }));

  const updatedCompanyAddress = screen.getAllByRole('article').find(article => within(article).queryByText('Công ty'));
  await user.click(within(updatedCompanyAddress).getByRole('button', { name: 'Xóa' }));
  await user.click(screen.getByRole('button', { name: 'Xác nhận xóa' }));
  expect(await screen.findByText('Nhà riêng')).toBeInTheDocument();
  expect(api.request).toHaveBeenCalledWith('/account/addresses/2', expect.objectContaining({ method: 'DELETE' }));
});
