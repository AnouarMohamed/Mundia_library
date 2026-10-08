import { NavLink } from "react-router-dom";

export function AdminNavigation() {
  return (
    <nav className="admin-local-nav" aria-label="Administration sections">
      <NavLink end to="/admin">Account review</NavLink>
      <NavLink to="/admin/circulation">Circulation desk</NavLink>
    </nav>
  );
}
