import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Icon } from '../../components/ui';
import { formatDate, formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { aiApi } from './aiApi';

// Khung chat tư vấn nội thất nổi góc dưới phải; chỉ gọi API khi khách mở/bấm lịch sử/gửi tin cho đỡ tốn request
export default function ChatWidget() {
  const { api } = useCommerce();
  const client = useMemo(() => aiApi(api), [api]);
  const [open, setOpen] = useState(false);
  const [conversationId, setConversationId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [historyOpen, setHistoryOpen] = useState(false); // Bật/tắt bảng xem lại mấy đoạn chat cũ
  const [conversations, setConversations] = useState([]);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const listRef = useRef(null);

  // Tự cuộn xuống cuối khung mỗi khi có tin nhắn mới
  useEffect(() => {
    if (open && !historyOpen && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [open, historyOpen, messages, busy]);

  // Mở danh sách các cuộc trò chuyện cũ của đúng tài khoản / phiên khách hiện tại
  async function toggleHistory() {
    if (historyOpen) {
      setHistoryOpen(false);
      return;
    }
    setHistoryOpen(true);
    setLoadingHistory(true);
    setError(null);
    try {
      const list = await client.conversations();
      setConversations(list || []);
    } catch (err) {
      setError(err);
    } finally {
      setLoadingHistory(false);
    }
  }

  // Chọn 1 đoạn chat cũ để đọc tiếp và nhắn tiếp (backend sẽ check lại chủ sở hữu)
  async function openConversation(publicId) {
    setBusy(true);
    setError(null);
    try {
      const detail = await client.conversation(publicId);
      setConversationId(detail.publicId);
      setMessages(detail.messages || []);
      setHistoryOpen(false);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  // Tạo phiên chat mới tinh
  function startNewChat() {
    setConversationId(null);
    setMessages([]);
    setHistoryOpen(false);
    setError(null);
  }

  async function submit(event) {
    event.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    setBusy(true);
    setError(null);
    setHistoryOpen(false);
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
            <div className="tv5-chat-head-actions">
              <button type="button" className="text-button" disabled={busy} onClick={toggleHistory}>
                {historyOpen ? 'Quay lại' : 'Lịch sử'}
              </button>
              <button type="button" className="text-button" disabled={busy} onClick={startNewChat}>
                Mới
              </button>
              <button type="button" className="icon-button" aria-label="Đóng khung chat" onClick={() => setOpen(false)}>
                <Icon name="close" size={18} />
              </button>
            </div>
          </div>

          {historyOpen ? (
            <div className="tv5-chat-messages">
              {loadingHistory ? (
                <p className="muted small">Đang tải các cuộc trò chuyện cũ…</p>
              ) : conversations.length ? (
                <div className="tv5-chat-history-list">
                  {conversations.map(item => (
                    <button
                      type="button"
                      key={item.publicId}
                      className={`tv5-chat-history-item${conversationId === item.publicId ? ' active' : ''}`}
                      onClick={() => openConversation(item.publicId)}
                    >
                      <strong>{item.title || 'Cuộc trò chuyện tư vấn'}</strong>
                      <span className="muted small">{item.updatedAt ? formatDate(item.updatedAt) : ''}</span>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="muted small">Chưa có cuộc trò chuyện cũ nào trong phiên này.</p>
              )}
            </div>
          ) : (
            <div className="tv5-chat-messages" ref={listRef}>
              {messages.length === 0 && (
                <p className="muted small">
                  Hỏi thoải mái về sofa, bàn trà, phòng khách, chất liệu gỗ hay tầm giá (VD: "Tư vấn bàn trà gỗ dưới 5 triệu").
                </p>
              )}
              {messages.map((msg, idx) => (
                <div key={idx} className={`tv5-chat-bubble ${msg.role === 'USER' ? 'user' : 'assistant'}`}>
                  <p>{msg.content}</p>
                  {msg.sources?.length > 0 && (
                    <ul className="tv5-chat-sources">
                      {msg.sources.map(src => (
                        <li key={`${src.productId}-${src.sku}`}>
                          {/* Có slug thì bấm nhảy thẳng qua trang chi tiết sp, ko có thì về trang Sale */}
                          <Link
                            to={src.productSlug ? `/products/${src.productSlug}` : '/sale'}
                            onClick={() => setOpen(false)}
                          >
                            <strong>{src.productName}</strong> ({src.variantName}) — {formatMoney(src.price)} · Tồn: {src.availableQuantity}
                            {src.dimensions ? ` · ${src.dimensions}` : ''}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              ))}
            </div>
          )}

          <ErrorNotice error={error} />
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
