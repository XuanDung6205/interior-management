import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Icon } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { aiApi } from './aiApi';

// Khung chat tư vấn nội thất nổi góc dưới phải; chỉ gọi API khi người dùng mở và gửi tin nhắn
export default function ChatWidget() {
  const { api } = useCommerce();
  const client = useMemo(() => aiApi(api), [api]);
  const [open, setOpen] = useState(false);
  const [conversationId, setConversationId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const listRef = useRef(null);

  // Tự cuộn xuống tin nhắn mới nhất mỗi khi mở khung chat hoặc có tin mới
  useEffect(() => {
    if (open && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [open, messages, busy]);

  async function submit(event) {
    event.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    setBusy(true);
    setError(null);
    setMessages(cur => [...cur, { role: 'USER', content: text, sources: [] }]);
    setInput('');
    try {
      const res = await client.sendMessage(conversationId, text);
      setConversationId(res.conversationId);
      setMessages(cur => [...cur, {
        role: 'ASSISTANT',
        content: res.reply,
        model: res.model,
        sources: res.sources || [],
      }]);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="tv5-chat-root">
      {open ? (
        <section className="panel tv5-chat-box" aria-label="Trợ lý tư vấn nội thất">
          <div className="panel-heading">
            <div>
              <h2>Tư vấn nội thất</h2>
              <span className="muted small">Giá & tồn kho cập nhật trực tiếp từ cửa hàng</span>
            </div>
            <button className="icon-button" aria-label="Đóng khung chat" onClick={() => setOpen(false)}>
              <Icon name="close" size={18} />
            </button>
          </div>
          <div className="tv5-chat-messages" ref={listRef}>
            {messages.length === 0 && (
              <p className="muted small">
                Hỏi về sofa, bàn trà, kích thước phòng hoặc tầm ngân sách (VD: "Tư vấn bàn trà gỗ dưới 5 triệu").
              </p>
            )}
            {messages.map((msg, idx) => (
              <div key={idx} className={`tv5-chat-bubble ${msg.role === 'USER' ? 'user' : 'assistant'}`}>
                <p>{msg.content}</p>
                {msg.sources?.length > 0 && (
                  <ul className="tv5-chat-sources">
                    {msg.sources.map(src => (
                      <li key={`${src.productId}-${src.sku}`}>
                        <Link to="/cart#catalog" onClick={() => setOpen(false)}>
                          <strong>{src.productName}</strong> ({src.variantName}) — {formatMoney(src.price)} · Tồn: {src.availableQuantity}
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
          <ErrorNotice error={error} />
          <p className="muted small tv5-chat-disclosure">Khi dịch vụ AI được bật, nội dung câu hỏi được gửi đến dịch vụ AI để tìm sản phẩm và trả lời.</p>
          <form className="tv5-chat-form" onSubmit={submit}>
            <label className="sr-only" htmlFor="tv5-chat-input">Nhập câu hỏi tư vấn</label>
            <input
              id="tv5-chat-input"
              maxLength={1000}
              value={input}
              disabled={busy}
              onChange={e => setInput(e.target.value)}
              placeholder="Nhập nhu cầu nội thất của bạn..."
            />
            <button className="button" disabled={busy || !input.trim()}>{busy ? 'Đang trả lời…' : 'Gửi'}</button>
          </form>
        </section>
      ) : (
        <button className="button tv5-chat-toggle" onClick={() => setOpen(true)}>
          Tư vấn nội thất
        </button>
      )}
    </div>
  );
}
