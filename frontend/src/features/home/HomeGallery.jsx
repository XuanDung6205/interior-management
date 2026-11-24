import { useEffect, useRef, useState } from 'react';
import { ErrorNotice, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';

function GalleryCarousel({ items }) {
  const stageRef = useRef(null);
  const pointerStart = useRef(null);
  const [rotation, setRotation] = useState(0);
  const [hovered, setHovered] = useState(false);
  const [visible, setVisible] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(() => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false);
  const angle = 360 / items.length;
  const depthFactor = items.length > 1 ? Math.max(1.3, 1 / (2 * Math.tan(Math.PI / items.length)) + 0.15) : 1.3;
  const active = ((rotation % items.length) + items.length) % items.length;

  useEffect(() => {
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (!media) return;
    const changed = event => setReducedMotion(event.matches);
    media.addEventListener?.('change', changed);
    return () => media.removeEventListener?.('change', changed);
  }, []);

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    if (!window.IntersectionObserver) { setVisible(true); return; }
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { threshold: 0.25 });
    observer.observe(stage);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (items.length < 2 || !hovered || reducedMotion || !visible) return;
    const timer = window.setInterval(() => setRotation(value => value + 1), 3000);
    return () => window.clearInterval(timer);
  }, [hovered, items.length, reducedMotion, visible]);

  function finishSwipe(event) {
    if (pointerStart.current === null) return;
    const distance = event.clientX - pointerStart.current;
    pointerStart.current = null;
    if (Math.abs(distance) > 45) setRotation(value => value + (distance < 0 ? 1 : -1));
  }

  return <div className="home-gallery-carousel" onPointerEnter={event => { if (event.pointerType === 'mouse') { setHovered(true); if (!reducedMotion && items.length > 1) setRotation(value => value + 1); } }} onPointerLeave={() => setHovered(false)}>
    <div className="home-gallery-stage" ref={stageRef} style={{ '--gallery-depth-factor': depthFactor }} tabIndex={0} role="region" aria-roledescription="carousel" aria-label="Ảnh cảm hứng không gian sống" onKeyDown={event => { if (event.key === 'ArrowRight') { event.preventDefault(); setRotation(value => value + 1); } else if (event.key === 'ArrowLeft') { event.preventDefault(); setRotation(value => value - 1); } }} onPointerDown={event => { pointerStart.current = event.clientX; }} onPointerUp={finishSwipe} onPointerCancel={() => { pointerStart.current = null; }}>
      <div className="home-gallery-ring" style={{ transform: `rotateY(${-rotation * angle}deg)` }}>
        {items.map((item, index) => <div className="home-gallery-slot" key={item.id} style={{ transform: `rotateY(${index * angle}deg)` }}>
          <figure className="home-gallery-slide" aria-hidden={index !== active}>
            <img src={item.imageUrl} alt={item.altText || item.title} loading={index === active ? 'eager' : 'lazy'} draggable="false" />
            <figcaption>{item.title}</figcaption>
          </figure>
        </div>)}
      </div>
    </div>
    <div className="home-gallery-footer home-container">
      <div className="home-gallery-progress" role="progressbar" aria-label="Tiến độ bộ ảnh" aria-valuemin={1} aria-valuemax={items.length} aria-valuenow={active + 1}><span style={{ width: `${100 * (active + 1) / items.length}%` }} /></div>
    </div>
  </div>;
}

export default function HomeGallery() {
  const { api } = useCommerce();
  const [gallery, setGallery] = useState(null);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    api.request('/commerce/home/gallery', { signal: controller.signal })
      .then(setGallery)
      .catch(value => { if (value.name !== 'AbortError') setError(value); });
    return () => controller.abort();
  }, [api, revision]);

  if (gallery?.items?.length === 0) return null;
  return <section className="home-gallery" aria-labelledby="home-gallery-title">
    <div className="home-gallery-heading"><span className="home-kicker">GÓC NHÀ CỦA NẾP NHÀ</span><h2 id="home-gallery-title">Một vòng quanh <em>tổ ấm.</em></h2><p>Những không gian gợi ý để bạn tìm thấy góc mình yêu.</p></div>
    {error && <div className="home-container"><ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} /></div>}
    {!gallery && !error && <Loading text="Đang tải bộ ảnh…" />}
    {gallery?.items?.length > 0 && <GalleryCarousel items={gallery.items} />}
  </section>;
}
