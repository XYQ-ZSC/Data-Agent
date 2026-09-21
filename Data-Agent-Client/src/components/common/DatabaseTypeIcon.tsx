import { useMemo, useState } from 'react';
import { Database } from 'lucide-react';

interface DatabaseTypeIconProps {
  dbType?: string;
  className?: string;
  fallbackClassName?: string;
}

interface DbIconRule {
  matchers: string[];
  iconUrl: string;
}

// Dameng (DM) has no official devicon; use an inline SVG badge so it does not
// depend on an external icon CDN. Load-error still falls back to the default icon.
const DM_ICON_URL = 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Crect%20width%3D%2224%22%20height%3D%2224%22%20rx%3D%225%22%20fill%3D%22%23C7362B%22%2F%3E%3Ctext%20x%3D%2212%22%20y%3D%2216.5%22%20text-anchor%3D%22middle%22%20font-family%3D%22Arial%2C%20sans-serif%22%20font-size%3D%2210%22%20font-weight%3D%22bold%22%20fill%3D%22%23FFFFFF%22%3EDM%3C%2Ftext%3E%3C%2Fsvg%3E';

const DB_ICON_RULES: DbIconRule[] = [
  { matchers: ['dameng', 'dm'], iconUrl: DM_ICON_URL },
  { matchers: ['mysql'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/mysql/mysql-original.svg' },
  { matchers: ['mariadb'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/mariadb/mariadb-original.svg' },
  { matchers: ['postgres', 'postgresql'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/postgresql/postgresql-original.svg' },
  { matchers: ['sqlserver', 'mssql'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/microsoftsqlserver/microsoftsqlserver-plain.svg' },
  { matchers: ['oracle'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/oracle/oracle-original.svg' },
  { matchers: ['sqlite'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/sqlite/sqlite-original.svg' },
  { matchers: ['clickhouse'], iconUrl: 'https://cdn.simpleicons.org/clickhouse/FFCC01' },
  { matchers: ['tidb'], iconUrl: 'https://cdn.simpleicons.org/tidb/EA4E20' },
  { matchers: ['db2'], iconUrl: 'https://cdn.simpleicons.org/ibmdb2/052FAD' },
  { matchers: ['mongodb', 'mongo'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/mongodb/mongodb-original.svg' },
  { matchers: ['redis'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/redis/redis-original.svg' },
];

export function DatabaseTypeIcon({
  dbType,
  className = 'w-4 h-4',
  fallbackClassName = 'text-blue-400',
}: DatabaseTypeIconProps) {
  const [failed, setFailed] = useState(false);

  const iconSrc = useMemo(() => {
    const normalized = (dbType || '').toLowerCase();
    if (!normalized) return '';

    const matchedRule = DB_ICON_RULES.find((rule) =>
      rule.matchers.some((matcher) => normalized.includes(matcher))
    );
    return matchedRule?.iconUrl ?? '';
  }, [dbType]);

  if (!iconSrc || failed) {
    return <Database className={`${className} ${fallbackClassName}`.trim()} />;
  }

  return (
    <img
      src={iconSrc}
      alt={dbType ? `${dbType} icon` : 'database icon'}
      className={`${className} object-contain`}
      onError={() => setFailed(true)}
      loading="lazy"
    />
  );
}
