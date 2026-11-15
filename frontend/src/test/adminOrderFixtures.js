import { order, orderSummary } from './checkoutFixtures';
export const customer = { id: 15, username: 'customer', fullName: 'Nguyễn An', email: 'customer@example.test' };
export const adminOrder = { ...order, customer, internalNote: 'Kho A xác nhận hàng', confirmedAt: null, completedAt: null, updatedAt: order.placedAt, assemblyRequested: true, allowedTransitions: ['CONFIRMED', 'CANCELLED'], blockedTransitions: [] };
export const adminSummary = { ...orderSummary, customer, recipientName: 'Nguyễn An', recipientPhone: '0900000000' };
