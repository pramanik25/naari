export default function SiteLogo() {
  return (
    <svg className="s-logo" viewBox="0 0 108 108" role="img" aria-label="Naari Kavach">
      <defs>
        <linearGradient id="ns-logo" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#FF4D7E" />
          <stop offset="0.55" stopColor="#8E2A8C" />
          <stop offset="1" stopColor="#3B2FA8" />
        </linearGradient>
      </defs>
      <rect width="108" height="108" fill="url(#ns-logo)" />
      <path
        fill="#fff"
        fillRule="evenodd"
        d="M54,29L75,36.500V52C75,65.500 66,75.800 54,80C42,75.800 33,65.500 33,52V36.500Z M54,63.500C54,63.500 43.500,56.800 43.500,50.300C43.500,47 46,44.600 49,44.600C51.100,44.600 53,45.900 54,47.800C55,45.900 56.900,44.600 59,44.600C62,44.600 64.500,47 64.500,50.300C64.500,56.800 54,63.500 54,63.500Z"
      />
    </svg>
  );
}
