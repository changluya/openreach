(() => {
  const params = new URLSearchParams(window.location.search);
  const error = document.getElementById('login-error');
  const logout = document.getElementById('logout-message');
  if (error) error.hidden = params.get('error') !== '1';
  if (logout) logout.hidden = params.get('logout') !== '1';
})();
