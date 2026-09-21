import { useEffect } from 'react';
import { useAuthStore } from '../../store/authStore';

interface RouteGuardProps {
    children: React.ReactNode;
}

export function RouteGuard({ children }: RouteGuardProps) {
    const { user, openLoginModal } = useAuthStore();

    useEffect(() => {
        if (!user) {
            openLoginModal();
        }
    }, [user, openLoginModal]);

    if (user) {
        return <>{children}</>;
    }

    return null;
}
