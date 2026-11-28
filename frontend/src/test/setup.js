import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
configure({ asyncUtilTimeout: 5000 });
import { afterEach } from 'vitest';
afterEach(cleanup);
