import { Icon } from './ui';
import './checkout-steps.css';

const steps = [['cart', 'Giỏ hàng'], ['orders', 'Thông tin giao hàng'], ['shield', 'Thanh toán']];

// current is the zero-based index of the step the shopper is on; earlier steps show as done.
export default function CheckoutSteps({ current }) {
  return <ol className="checkout-steps" aria-label="Các bước mua hàng">
    {steps.map(([icon, label], index) => <li key={label} className={index === current ? 'is-current' : index < current ? 'is-done' : ''} aria-current={index === current ? 'step' : undefined}>
      <span className="checkout-step-icon"><Icon name={index < current ? 'check' : icon} size={18} /></span>
      <span><small>Bước {index + 1}</small>{label}</span>
    </li>)}
  </ol>;
}
