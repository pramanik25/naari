"use client";

import { useRef, useState } from "react";

// The API server keeps files on ephemeral disk, so a restart/deploy or retention can remove them.
const UNAVAILABLE = "File no longer available on the API server.";

// Photo or video tile. Images open full size in a new tab.
export function EvidenceTile({ url, kind, label }) {
  const [failed, setFailed] = useState(false);
  return (
    <figure className="evidence-tile">
      {failed ? (
        <p className="evidence-missing">{UNAVAILABLE}</p>
      ) : kind === "video" ? (
        <video src={url} controls preload="metadata" onError={() => setFailed(true)} />
      ) : (
        <a href={url} target="_blank" rel="noreferrer">
          <img src={url} alt={`Photo captured ${label}`} loading="lazy" onError={() => setFailed(true)} />
        </a>
      )}
      <figcaption className="meta">{label}</figcaption>
    </figure>
  );
}

// Audio is recorded in ~10 s chunks; when one finishes the next one starts, so the
// recording can be listened to as a whole.
export function EvidenceAudioList({ items }) {
  const players = useRef([]);
  const [failed, setFailed] = useState({});

  function playNext(i) {
    for (let j = i + 1; j < items.length; j++) {
      const el = players.current[j];
      if (el && !failed[items[j].id]) {
        el.play().catch(() => {});
        return;
      }
    }
  }

  return (
    <ol className="evidence-audio">
      {items.map((a, i) => (
        <li key={a.id}>
          <span className="dim">{a.label}</span>
          {failed[a.id] ? (
            <span className="evidence-missing">{UNAVAILABLE}</span>
          ) : (
            <audio
              ref={(el) => (players.current[i] = el)}
              src={a.url}
              controls
              preload="none"
              onEnded={() => playNext(i)}
              onError={() => setFailed((f) => ({ ...f, [a.id]: true }))}
            />
          )}
        </li>
      ))}
    </ol>
  );
}
